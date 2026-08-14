package com.warden.controlledsandbox.framework.packagemanager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.IdentityObjectRewriter;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.contract.CameraServiceContract;
import com.warden.controlledsandbox.framework.contract.InvocationMethodMatcher;
import com.warden.controlledsandbox.framework.contract.NfcServiceContract;
import com.warden.controlledsandbox.framework.contract.WebViewProviderServiceContract;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNfcProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Virtual package query surface layered over the host IPackageManager Binder interface. */
public final class PackageManagerInvocationHandler implements InvocationHandler {
    private final Object delegate;
    private final GuestIdentity identity;
    private final VirtualPackageMetadata metadata;
    private final com.warden.controlledsandbox.framework.identity.VirtualPackageUniverse universe;

    PackageManagerInvocationHandler(Object delegate, GuestIdentity identity) {
        this.delegate = delegate;
        this.identity = identity;
        this.metadata = identity.packageMetadata();
        this.universe = identity.packageUniverse();
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) return method.invoke(delegate, args);
        Object virtual = virtualResult(method, name, method.getReturnType(), args);
        boolean hostFeaturePassThrough = virtual == HostFeaturePassThrough.VALUE;
        if (!hostFeaturePassThrough && virtual != NoResult.VALUE) return virtual;
        if (!hostFeaturePassThrough && isQueryMethod(name)) {
            return isolatedQueryDefault(method.getReturnType());
        }

        Object[] rewritten = args == null ? null : args.clone();
        IdentityObjectRewriter.RewriteScope scope = IdentityObjectRewriter.rewriteArguments(rewritten, identity);
        try {
            try { return IdentityObjectRewriter.rewriteResult(method.invoke(delegate, rewritten), identity); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        } finally {
            scope.close();
        }
    }

    private Object virtualResult(Method method, String methodName, Class<?> returnType, Object[] args)
            throws Throwable {
        // The platform has overloads which carry the caller package/uid in addition to the
        // feature name. Those identity arguments describe the querying process, not a package
        // target. Resolve controlled features before the generic host-package deny-first check.
        if (isSystemFeatureMethod(methodName)) {
            Object feature = virtualSystemFeature(args);
            if (feature != NoResult.VALUE) return feature;
        }
        String targetPackage = "resolveContentProvider".equals(methodName) ? ""
                : targetPackageName(args);
        boolean virtualTarget = !targetPackage.isEmpty()
                && universe.isVisibleTo(identity.packageName(), targetPackage);
        boolean virtualAuthority = "resolveContentProvider".equals(methodName)
                && universe.provider(identity.packageName(), firstString(args), firstLong(args, 0L)) != null;
        boolean guestTarget = containsGuestPackage(args) || ownsGuestAuthority(args)
                || virtualTarget || virtualAuthority;
        PackageVisibilityClass visibility = targetPackage.isEmpty()
                ? null : PackageVisibilityPolicy.classify(identity, targetPackage);
        if (!guestTarget && containsHostPackage(args)
                && "getServiceInfo".equals(methodName)) {
            // A Guest may probe the host stub service while attaching an Activity. The
            // stub is not a Guest service and must remain hidden; returning an absent
            // service lets Android's normal NameNotFound path handle the optional probe.
            android.util.Log.i("CS_PM_STUB_COMPONENT_ABSENT", "method=" + methodName);
            return null;
        }
        if (visibility != null && !guestTarget
                && PackageVisibilityPolicy.reportsAbsentWithoutProjector(visibility)
                && isPackageIdentityMethod(methodName)
                && !isControlledWebViewProvider(targetPackage)) {
            // System and shared-dependency roles are not Guest packages. Without a
            // sanitized projector, report the Android NameNotFound shape. WebView keeps
            // its existing controlled projector below.
            android.util.Log.i("CS_PM_OPTIONAL_DEPENDENCY",
                    "class=" + visibility + " absent=" + targetPackage);
            return hiddenPackageResult(methodName, returnType);
        }
        if (visibility != null && !guestTarget
                && PackageVisibilityPolicy.deniesIdentity(visibility)
                && !isSystemFeatureMethod(methodName)) {
            android.util.Log.w("CS_PM_HIDDEN_BLOCK", "method=" + methodName
                    + " class=" + visibility + " package=" + targetPackage
                    + " first=" + firstString(args), null);
            return hiddenPackageResult(methodName, returnType);
        }
        boolean hiddenTarget = containsHiddenPackage(args);
        if (hiddenTarget && !guestTarget && !isSystemFeatureMethod(methodName)) {
            android.util.Log.w("CS_PM_HIDDEN_BLOCK", "method=" + methodName
                    + " package=" + packageArgument(args) + " first=" + firstString(args), null);
            return hiddenPackageResult(methodName, returnType);
        }
        if (isControlledUnavailableWebViewDependency(methodName, args)) {
            // IPackageManager is a Binder-shaped interface whose method does not declare
            // NameNotFoundException. Returning null lets ApplicationPackageManager translate the
            // absence into its normal checked exception, which Chromium's PackageUtils handles.
            return null;
        }
        Object webViewPackage = webViewPackageQuery(method, args);
        if (webViewPackage != NoResult.VALUE) return webViewPackage;
        Object webViewComponent = webViewComponentQuery(method, args);
        if (webViewComponent != NoResult.VALUE) return webViewComponent;
        switch (methodName) {
            case "getApplicationInfo":
            case "getApplicationInfoAsUser":
                if (!guestTarget) return hiddenPackageResult(methodName, returnType);
                return identity.packageName().equals(targetPackage) ? metadata.applicationInfo()
                        : universe.applicationInfo(identity.packageName(), targetPackage);
            case "getPackageInfo":
            case "getPackageInfoAsUser":
            case "getPackageInfoVersioned":
                if (!guestTarget) return hiddenPackageResult(methodName, returnType);
                return identity.packageName().equals(targetPackage)
                        ? metadata.packageInfo(firstLong(args, ~0L))
                        : universe.packageInfo(identity.packageName(), targetPackage,
                                firstLong(args, ~0L));
            case "getPackageUid":
            case "getPackageUidAsUser":
                if (!guestTarget) return hiddenPackageResult(methodName, returnType);
                return identity.packageName().equals(targetPackage) ? identity.virtualUid()
                        : universe.packageUid(identity.packageName(), targetPackage);
            case "isPackageAvailable":
                if (!guestTarget) return Boolean.FALSE;
                return identity.packageName().equals(targetPackage) ? metadata.enabled()
                        : universe.applicationInfo(identity.packageName(), targetPackage) != null;
            case "getInstallerPackageName":
                if (!guestTarget) return null;
                VirtualPackageMetadata installerTarget = universe.packageMetadata(targetPackage);
                return installerTarget == null ? null : installerTarget.installerPackageName();
            case "getInstallSourceInfo":
                return guestTarget ? installSourceInfo(returnType) : null;
            case "checkPermission":
                if (!guestTarget) return PackageManager.PERMISSION_DENIED;
                String permission = firstString(args);
                return identity.permissionPolicy().isGranted(permission)
                        ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
            case "getPackagesForUid":
                return universe.packagesForUid(identity.packageName(), firstInt(args, -1));
            case "getNameForUid":
                return universe.packageForUid(identity.packageName(), firstInt(args, -1));
            case "getActivityInfo":
                return component(args, VirtualPackageMetadata.Type.ACTIVITY);
            case "getReceiverInfo":
                return component(args, VirtualPackageMetadata.Type.RECEIVER);
            case "getServiceInfo":
                return component(args, VirtualPackageMetadata.Type.SERVICE);
            case "getProviderInfo":
                return component(args, VirtualPackageMetadata.Type.PROVIDER);
            case "getInstrumentationInfo": {
                ComponentName name = firstComponent(args);
                if (name == null) throw new IllegalArgumentException("VIRTUAL_COMPONENT_REQUIRED");
                if (!identity.packageName().equals(name.getPackageName())) {
                    return hiddenPackageResult(methodName, returnType);
                }
                return metadata.instrumentationInfo(name, firstLong(args, 0L));
            }
            case "queryInstrumentation":
                return metadata.adaptCollection(metadata.queryInstrumentation(
                        firstString(args), firstLong(args, 0L)), returnType);
            case "getSystemSharedLibraryNames":
                return metadata.resolvedSharedLibraryNames().toArray(new String[0]);
            case "getSharedLibraries":
                return metadata.adaptCollection(metadata.sharedLibraryInfoObjects(), returnType);
            case "getDeclaredSharedLibraries":
                return metadata.adaptCollection(Collections.emptyList(), returnType);
            case "resolveContentProvider": {
                String authority = firstString(args);
                return universe.provider(identity.packageName(), authority, firstLong(args, 0L));
            }
            case "resolveIntent":
            case "resolveActivity": {
                Intent intent = firstIntent(args);
                return universe.resolve(identity.packageName(), intent,
                        VirtualPackageMetadata.Type.ACTIVITY, firstLong(args, 0L));
            }
            case "resolveService": {
                Intent intent = firstIntent(args);
                return universe.resolve(identity.packageName(), intent,
                        VirtualPackageMetadata.Type.SERVICE, firstLong(args, 0L));
            }
            case "queryIntentActivities":
                return query(returnType, args, VirtualPackageMetadata.Type.ACTIVITY);
            case "queryIntentReceivers":
            case "queryBroadcastReceivers":
                return query(returnType, args, VirtualPackageMetadata.Type.RECEIVER);
            case "queryIntentServices":
                return query(returnType, args, VirtualPackageMetadata.Type.SERVICE);
            case "getInstalledPackages":
                return metadata.adaptCollection(universe.installedPackages(identity.packageName(),
                        firstLong(args, ~0L)), returnType);
            case "getPackagesHoldingPermissions":
                return metadata.adaptCollection(holdsAnyPermission(args)
                        ? Collections.singletonList(metadata.packageInfo(firstLong(args, 0x1000L)))
                        : Collections.emptyList(), returnType);
            case "getInstalledApplications": {
                long flags = firstLong(args, 0L);
                return metadata.adaptCollection(universe.installedApplications(identity.packageName(), flags),
                        returnType);
            }
            case "getPackageGids":
            case "getPackageGidsEtc":
                return new int[0];
            case "queryContentProviders": {
                long flags = firstLong(args, 0L);
                return metadata.adaptCollection(universe.queryContentProviders(identity.packageName(), flags),
                        returnType);
            }
            case "getComponentEnabledSetting": {
                ComponentName component = firstComponent(args);
                return component != null && identity.packageName().equals(component.getPackageName())
                        ? metadata.componentEnabledSetting(component) : NoResult.VALUE;
            }
            case "setApplicationEnabledSetting":
            case "setPackageStoppedState":
                if (guestTarget) {
                    return null;
                }
                return NoResult.VALUE;
            case "setComponentEnabledSetting": {
                ComponentName component = firstComponent(args);
                if (component != null && identity.packageName().equals(component.getPackageName())) {
                    metadata.setComponentEnabledSetting(component, firstIntAfterComponent(args));
                    return null;
                }
                if (guestTarget) return null;
                return NoResult.VALUE;
            }
            case "hasSigningCertificate":
                if (!guestTarget) return NoResult.VALUE;
                return signingDigestMatches(args, metadata.signatureSha256());
            default:
                return NoResult.VALUE;
        }
    }

    private Object virtualSystemFeature(Object[] args) {
        if (WebViewProviderServiceContract.WEBVIEW_FEATURE.equals(firstString(args))) {
            VirtualWebViewProfileSnapshot profile =
                    identity.virtualServices().compatibilityProfile().webView();
            if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) {
                return HostFeaturePassThrough.VALUE;
            }
            return !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())
                    && !profile.providerPackage().isEmpty();
        }
        // A radio-less emulator commonly reports no telephony feature, which makes the public
        // TelephonyManager short-circuit before it reaches the virtual Binder.  Project the
        // feature from the Guest's explicit telephony profile instead of inheriting the Host
        // hardware inventory.  This is intentionally generic and package-independent.
        String feature = firstString(args);
        if ("android.hardware.telephony".equals(feature)
                || "android.hardware.telephony.radio.access".equals(feature)) {
            try {
                var profile = identity.virtualServices().deviceServiceProfile().telephony();
                if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) {
                    return HostFeaturePassThrough.VALUE;
                }
                return !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())
                        && (!profile.slots().isEmpty() || !profile.cells().isEmpty());
            } catch (IllegalStateException unavailable) {
                return NoResult.VALUE;
            }
        }
        Object nfcFeature = virtualNfcFeature(args);
        if (nfcFeature != NoResult.VALUE) return nfcFeature;
        Object cameraFeature = virtualCameraFeature(args);
        if (cameraFeature != NoResult.VALUE) return cameraFeature;
        return NoResult.VALUE;
    }

    private static boolean isSystemFeatureMethod(String methodName) {
        return InvocationMethodMatcher.startsWith(methodName, "hasSystemFeature");
    }

    /**
     * Projects the platform WebView APK as one virtual package while keeping the raw provider
     * package hidden from ordinary Guest package queries. WebViewFactory asks PackageManager for
     * the provider package after receiving it from IWebViewUpdateService; returning a synthetic
     * PackageInfo is insufficient because the framework must load the trusted provider code and
     * native library from its source APK.
     *
     * <p>Only the two AOSP provider package names are candidates. The selected package must be a
     * system or updated-system package. The returned package name remains the policy's virtual
     * name, while source/library metadata is retained solely so the framework can load that
     * controlled platform provider. Provider data is redirected into the Guest data root.</p>
     */
    private Object webViewPackageQuery(Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        if (!("getApplicationInfo".equals(methodName)
                || "getApplicationInfoAsUser".equals(methodName)
                || "getPackageInfo".equals(methodName)
                || "getPackageInfoAsUser".equals(methodName)
                || "getPackageInfoVersioned".equals(methodName))) {
            return NoResult.VALUE;
        }
        VirtualWebViewProfileSnapshot profile;
        try {
            profile = identity.virtualServices().compatibilityProfile().webView();
        } catch (IllegalStateException unavailable) {
            return NoResult.VALUE;
        }
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) {
            return NoResult.VALUE;
        }
        String requested = firstString(args);
        if (requested.isEmpty() || !profile.providerPackage().equals(requested)) {
            return NoResult.VALUE;
        }
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())) {
            return hiddenPackageResult(methodName, method.getReturnType());
        }
        int packageIndex = firstStringIndex(args);
        if (packageIndex < 0) return NoResult.VALUE;

        try {
            Object raw = method.invoke(delegate, args);
            if (raw == null) return hiddenPackageResult(methodName, method.getReturnType());
            return projectWebViewPackage(raw, profile);
        } catch (InvocationTargetException error) {
            // A configured provider which is not installed is a real compatibility failure. Do
            // not silently substitute another platform package: WebView resource/package
            // identity must remain exact and the contract must fail closed.
            throw error.getCause();
        }
    }

    private boolean isControlledUnavailableWebViewDependency(String methodName, Object[] args) {
        if (!("getPackageInfo".equals(methodName)
                || "getPackageInfoAsUser".equals(methodName)
                || "getPackageInfoVersioned".equals(methodName))) return false;
        try {
            VirtualWebViewProfileSnapshot profile =
                    identity.virtualServices().compatibilityProfile().webView();
            return !VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())
                    && !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())
                    && WebViewProviderServiceContract.isControlledUnavailableDependency(
                            profile.providerPackage(), firstString(args));
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    private boolean isControlledWebViewProvider(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            VirtualWebViewProfileSnapshot profile =
                    identity.virtualServices().compatibilityProfile().webView();
            return profile.providerPackage() != null && profile.providerPackage().equals(packageName);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean isPackageIdentityMethod(String methodName) {
        return "getApplicationInfo".equals(methodName)
                || "getApplicationInfoAsUser".equals(methodName)
                || "getPackageInfo".equals(methodName)
                || "getPackageInfoAsUser".equals(methodName)
                || "getPackageInfoVersioned".equals(methodName)
                || "getPackageUid".equals(methodName)
                || "getPackageUidAsUser".equals(methodName)
                || "getActivityInfo".equals(methodName)
                || "getReceiverInfo".equals(methodName)
                || "getServiceInfo".equals(methodName)
                || "getProviderInfo".equals(methodName)
                || "isPackageAvailable".equals(methodName)
                || "checkPermission".equals(methodName);
    }

    private Object hiddenPackageResult(String methodName, Class<?> returnType) {
        return HiddenPackageResultMapper.map(methodName, returnType, metadata::adaptCollection);
    }

    private Object projectWebViewPackage(Object raw, VirtualWebViewProfileSnapshot profile) {
        if (raw instanceof ApplicationInfo application) {
            return projectWebViewApplication(application, profile);
        }
        if (raw instanceof PackageInfo packageInfo) {
            PackageInfo projected = new PackageInfo();
            projected.packageName = profile.providerPackage();
            projected.versionName = packageInfo.versionName;
            projected.versionCode = packageInfo.versionCode;
            projected.signatures = packageInfo.signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                projected.signingInfo = packageInfo.signingInfo;
            }
            projected.requestedPermissions = packageInfo.requestedPermissions;
            projected.firstInstallTime = packageInfo.firstInstallTime;
            projected.lastUpdateTime = packageInfo.lastUpdateTime;
            copyField(packageInfo, projected, "versionCodeMajor");
            if (packageInfo.applicationInfo != null) {
                projected.applicationInfo = projectWebViewApplication(
                        packageInfo.applicationInfo, profile);
            }
            return projected;
        }
        throw new IllegalStateException("VIRTUAL_WEBVIEW_PACKAGE_SIGNATURE_UNSUPPORTED"
                + ":" + raw);
    }

    /**
     * Exposes only the renderer service metadata required by Chromium's child-process launcher.
     * The returned copy deliberately retains the provider APK's source/native-library and UID
     * metadata: ActivityManager uses those fields to launch the trusted platform renderer.
     * No other provider component is allowed through this path.
     */
    private Object webViewComponentQuery(Method method, Object[] args) throws Throwable {
        if (!"getServiceInfo".equals(method.getName())) return NoResult.VALUE;
        ComponentName component = firstComponent(args);
        if (component == null) return NoResult.VALUE;
        VirtualWebViewProfileSnapshot profile;
        try {
            profile = identity.virtualServices().compatibilityProfile().webView();
        } catch (IllegalStateException unavailable) {
            return NoResult.VALUE;
        }
        boolean renderer = WebViewProviderServiceContract.isRendererService(
                profile.providerPackage(), component);
        boolean providerService = WebViewProviderServiceContract.isProviderService(
                profile.providerPackage(), component);
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())
                || VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())
                || (!renderer && !providerService)) {
            return NoResult.VALUE;
        }
        try {
            Object raw = method.invoke(delegate, args);
            if (!(raw instanceof ServiceInfo service)) {
                throw new IllegalStateException("VIRTUAL_WEBVIEW_SERVICE_UNAVAILABLE");
            }
            if (!component.getClassName().equals(service.name)
                    || !profile.providerPackage().equals(service.packageName)) {
                throw new IllegalStateException("VIRTUAL_WEBVIEW_SERVICE_MISMATCH");
            }
            ServiceInfo projected = new ServiceInfo();
            projected.name = service.name;
            projected.packageName = service.packageName;
            projected.processName = service.processName;
            projected.exported = service.exported;
            projected.enabled = service.enabled;
            projected.applicationInfo = service.applicationInfo;
            projected.permission = service.permission;
            projected.flags = service.flags;
            return projected;
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private ApplicationInfo projectWebViewApplication(
            ApplicationInfo source, VirtualWebViewProfileSnapshot profile) {
        ApplicationInfo projected = new ApplicationInfo(source);
        // The profile package is an allowlisted, exact platform provider identity. Keeping the
        // same value in PackageInfo and ApplicationInfo is required by WebViewFactory and by the
        // provider resource table; arbitrary Host package names are never accepted here.
        projected.packageName = profile.providerPackage();
        projected.uid = identity.virtualUid();
        String guestDataDir = identity.applicationInfo().dataDir;
        if (guestDataDir != null && !guestDataDir.trim().isEmpty()) {
            projected.dataDir = new java.io.File(guestDataDir, "webview/provider").getAbsolutePath();
        }
        return projected;
    }

    private static void copyField(Object source, Object target, String name) {
        if (source == null || target == null) return;
        try {
            java.lang.reflect.Field sourceField = findField(source.getClass(), name);
            java.lang.reflect.Field targetField = findField(target.getClass(), name);
            sourceField.setAccessible(true);
            targetField.setAccessible(true);
            targetField.set(target, sourceField.get(source));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name)
            throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private Object virtualNfcFeature(Object[] args) {
        if (!NfcServiceContract.isNfcFeature(firstString(args))) return NoResult.VALUE;
        VirtualNfcProfileSnapshot profile;
        try {
            profile = identity.virtualServices().peripheralServicesProfile().nfc();
        } catch (IllegalStateException unavailable) {
            String message = unavailable.getMessage();
            if ("VIRTUAL_PERIPHERAL_SERVICES_PROFILE_AUTHORITY_REQUIRED".equals(message)
                    || "VIRTUAL_PERIPHERAL_SERVICES_PROFILE_NOT_AVAILABLE".equals(message)) {
                return NoResult.VALUE;
            }
            throw unavailable;
        }
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) {
            return HostFeaturePassThrough.VALUE;
        }
        return NfcServiceContract.guestFeatureEnabled(profile);
    }

    private Object virtualCameraFeature(Object[] args) {
        String featureName = firstString(args);
        if (!CameraServiceContract.isCameraFeature(featureName)) return NoResult.VALUE;
        VirtualCameraProfileSnapshot profile;
        try {
            profile = identity.virtualServices().peripheralServicesProfile().camera();
        } catch (IllegalStateException unavailable) {
            String message = unavailable.getMessage();
            if ("VIRTUAL_PERIPHERAL_SERVICES_PROFILE_AUTHORITY_REQUIRED".equals(message)
                    || "VIRTUAL_PERIPHERAL_SERVICES_PROFILE_NOT_AVAILABLE".equals(message)) {
                return NoResult.VALUE;
            }
            throw unavailable;
        }
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) {
            return HostFeaturePassThrough.VALUE;
        }
        return CameraServiceContract.guestFeatureEnabled(profile, featureName);
    }

    private Object component(Object[] args, VirtualPackageMetadata.Type type) {
        ComponentName name = firstComponent(args);
        if (name == null) throw new IllegalArgumentException("VIRTUAL_COMPONENT_REQUIRED");
        if (!universe.isVisibleTo(identity.packageName(), name.getPackageName())) {
            android.util.Log.w("CS_PM_COMPONENT_BLOCK", "type=" + type + " component="
                    + name.flattenToShortString(), null);
            return null;
        }
        return universe.componentInfo(identity.packageName(), name, type, firstLong(args, 0L));
    }

    private Object query(Class<?> returnType, Object[] args, VirtualPackageMetadata.Type type) {
        Intent intent = firstIntent(args);
        List<ResolveInfo> matches = universe.query(identity.packageName(), intent, type,
                firstLong(args, 0L));
        return metadata.adaptCollection(matches, returnType);
    }

    private boolean targetsGuest(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        if (component != null) return identity.packageName().equals(component.getPackageName());
        return identity.packageName().equals(intent.getPackage());
    }

    private boolean holdsAnyPermission(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (!(arg instanceof String[])) continue;
            for (String permission : (String[]) arg) {
                if (identity.permissionPolicy().isGranted(permission)) return true;
            }
        }
        return false;
    }

    private Object installSourceInfo(Class<?> returnType) {
        if (returnType == null || returnType == Object.class || metadata.installerPackageName().isEmpty()) return null;
        for (java.lang.reflect.Constructor<?> constructor : returnType.getDeclaredConstructors()) {
            try {
                Class<?>[] types = constructor.getParameterTypes();
                Object[] values = new Object[types.length];
                int stringIndex = 0;
                for (int index = 0; index < types.length; index++) {
                    if (types[index] == String.class) {
                        values[index] = stringIndex++ == 0 ? metadata.installerPackageName()
                                : (stringIndex >= 3 ? metadata.installerPackageName() : null);
                    } else if (types[index] == int.class || types[index] == Integer.class) {
                        values[index] = 0;
                    } else if (types[index] == boolean.class || types[index] == Boolean.class) {
                        values[index] = false;
                    } else {
                        values[index] = null;
                    }
                }
                constructor.setAccessible(true);
                return constructor.newInstance(values);
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return null;
    }



    private boolean containsHiddenPackage(Object[] args) {
        if (args == null) return false;
        java.util.Set<String> hidden = new java.util.LinkedHashSet<>();
        hidden.add(identity.hostPackageName());
        try {
            com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot policy =
                    identity.virtualServices().compatibilityProfile().detection();
            if (!com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot.MODE_HOST.equals(policy.mode())) {
                hidden.addAll(policy.hiddenPackageNames());
                if (!policy.hideHostPackage()) hidden.remove(identity.hostPackageName());
            }
        } catch (IllegalStateException ignored) { }
        for (Object arg : args) {
            if (arg instanceof String && hidden.contains(arg)) return true;
            if (arg instanceof ComponentName && hidden.contains(((ComponentName) arg).getPackageName())) return true;
            if (arg instanceof Intent) {
                Intent intent = (Intent) arg;
                if (hidden.contains(intent.getPackage())) return true;
                ComponentName component = intent.getComponent();
                if (component != null && hidden.contains(component.getPackageName())) return true;
            }
        }
        return false;
    }

    private boolean containsHostPackage(Object[] args) {
        if (args == null || identity.hostPackageName() == null
                || identity.hostPackageName().trim().isEmpty()) return false;
        for (Object arg : args) {
            if (arg instanceof ComponentName component
                    && identity.hostPackageName().equals(component.getPackageName())) return true;
            if (arg instanceof String value && identity.hostPackageName().equals(value)) return true;
        }
        return false;
    }

    private boolean ownsGuestAuthority(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg instanceof String value && metadata.ownsAuthority(value)) return true;
            if (arg instanceof ProviderInfo info
                    && metadata.ownsAuthority(info.authority == null ? "" : info.authority)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsGuestPackage(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (identity.packageName().equals(arg)) return true;
            if (arg instanceof ComponentName
                    && identity.packageName().equals(((ComponentName) arg).getPackageName())) return true;
            if (arg instanceof Intent) {
                Intent intent = (Intent) arg;
                if (identity.packageName().equals(intent.getPackage())) return true;
                ComponentName component = intent.getComponent();
                if (component != null && identity.packageName().equals(component.getPackageName())) return true;
            }
            if (arg != null && arg.getClass().getName().endsWith("VersionedPackage")) {
                try {
                    Method getter = arg.getClass().getMethod("getPackageName");
                    if (identity.packageName().equals(getter.invoke(arg))) return true;
                } catch (ReflectiveOperationException ignored) { }
            }
        }
        return false;
    }

    private static boolean isQueryMethod(String name) {
        return InvocationMethodMatcher.startsWith(name, "get", "query", "resolve", "check", "is", "has");
    }

    private static Object isolatedQueryDefault(Class<?> returnType) {
        if (returnType == null || returnType == void.class) return null;
        if (returnType == boolean.class || returnType == Boolean.class) return false;
        if (returnType == int.class || returnType == Integer.class) return -1;
        if (returnType == long.class || returnType == Long.class) return -1L;
        if (returnType == float.class || returnType == Float.class) return 0F;
        if (returnType == double.class || returnType == Double.class) return 0D;
        if (returnType.isArray()) return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
        if (java.util.List.class.isAssignableFrom(returnType)) return Collections.emptyList();
        if (java.util.Set.class.isAssignableFrom(returnType)) return Collections.emptySet();
        if (java.util.Map.class.isAssignableFrom(returnType)) return Collections.emptyMap();
        return null;
    }

    private static String firstString(Object[] args) {
        if (args == null) return "";
        for (Object arg : args) if (arg instanceof String) return (String) arg;
        return "";
    }

    private static String packageArgument(Object[] args) {
        if (args == null) return "";
        for (Object arg : args) {
            if (arg instanceof ComponentName component) return component.getPackageName();
            if (arg instanceof String value && value.contains(".")) return value;
            if (arg instanceof android.content.pm.VersionedPackage versioned) {
                return versioned.getPackageName();
            }
        }
        return "";
    }

    private static String targetPackageName(Object[] args) {
        if (args == null) return "";
        for (Object arg : args) {
            if (arg instanceof ComponentName component) {
                String name = component.getPackageName();
                if (name != null && !name.isEmpty()) return name;
            }
            if (arg instanceof android.content.pm.VersionedPackage versioned) {
                String name = versioned.getPackageName();
                if (name != null && !name.isEmpty()) return name;
            }
        }
        for (Object arg : args) {
            if (!(arg instanceof String value) || value.isEmpty()) continue;
            if ("android".equals(value)) return value;
            if (!value.contains(".")) continue;
            if (value.startsWith("android.permission.") || value.startsWith("android.intent.")) {
                continue;
            }
            return value;
        }
        return "";
    }

    private static int firstStringIndex(Object[] args) {
        if (args == null) return -1;
        for (int index = 0; index < args.length; index++) {
            if (args[index] instanceof String) return index;
        }
        return -1;
    }
    private static long firstLong(Object[] args, long fallback) {
        if (args == null) return fallback;
        for (Object arg : args) {
            if (arg instanceof Long) return (Long) arg;
        }
        for (Object arg : args) if (arg instanceof Integer) return ((Integer) arg).longValue();
        return fallback;
    }
    private static boolean signingDigestMatches(Object[] args, String expectedHex) {
        if (args == null || expectedHex == null || expectedHex.isEmpty()) return false;
        for (Object arg : args) {
            if (!(arg instanceof byte[])) continue;
            byte[] value = (byte[]) arg;
            StringBuilder hex = new StringBuilder(value.length * 2);
            for (byte item : value) hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return expectedHex.equalsIgnoreCase(hex.toString());
        }
        return false;
    }

    private static int firstInt(Object[] args, int fallback) {
        if (args == null) return fallback;
        for (Object arg : args) if (arg instanceof Integer) return (Integer) arg;
        return fallback;
    }
    private static ComponentName firstComponent(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) if (arg instanceof ComponentName) return (ComponentName) arg;
        return null;
    }

    private static int firstIntAfterComponent(Object[] args) {
        if (args == null) return 0;
        boolean seenComponent = false;
        for (Object arg : args) {
            if (arg instanceof ComponentName) {
                seenComponent = true;
                continue;
            }
            if (seenComponent && arg instanceof Integer) return (Integer) arg;
        }
        return firstInt(args, 0);
    }
    private static Intent firstIntent(Object[] args) {
        if (args == null) return new Intent();
        for (Object arg : args) if (arg instanceof Intent) return (Intent) arg;
        return new Intent();
    }
    private static String firstAuthority(String value) {
        if (value == null) return "";
        int separator = value.indexOf(';');
        return separator < 0 ? value : value.substring(0, separator);
    }

    private enum NoResult { VALUE }
    private enum HostFeaturePassThrough { VALUE }
}
