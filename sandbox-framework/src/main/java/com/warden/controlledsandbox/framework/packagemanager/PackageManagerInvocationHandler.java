package com.warden.controlledsandbox.framework.packagemanager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.IdentityObjectRewriter;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPackageUniverse;
import com.warden.controlledsandbox.framework.binder.BinderIdentity;
import com.warden.controlledsandbox.framework.binder.BinderInterceptionFoundation;
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
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Virtual package query surface layered over the host IPackageManager Binder interface. */
public final class PackageManagerInvocationHandler implements InvocationHandler {
    private final Object delegate;
    private final GuestIdentity identity;
    private final VirtualPackageMetadata metadata;
    private final com.warden.controlledsandbox.framework.identity.VirtualPackageUniverse universe;
    private volatile BinderInterceptionFoundation binderBoundary;

    PackageManagerInvocationHandler(Object delegate, GuestIdentity identity) {
        this.delegate = delegate;
        this.identity = identity;
        this.metadata = identity.packageMetadata();
        this.universe = identity.packageUniverse();
        this.binderBoundary = null;
    }

    /** Installs the common low-level boundary below the PMS semantic adapter. */
    void attachBinderBoundary(Object localInterface) {
        if (binderBoundary != null || !(delegate instanceof android.os.IInterface typed)) return;
        android.os.IBinder binder = typed.asBinder();
        if (binder == null) throw new IllegalStateException("BINDER_PMS_AS_BINDER_UNAVAILABLE");
        binderBoundary = BinderInterceptionFoundation.builder(
                        binder, BinderIdentity.fromGuestIdentity(identity))
                .serviceName("packagemanager")
                .localInterface(localInterface)
                .sessionFence(identity.binderSessionFence())
                .build();
    }

    void invalidateBinderBoundary(String reason) {
        BinderInterceptionFoundation boundary = binderBoundary;
        if (boundary != null) boundary.invalidate(reason);
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) return method.invoke(delegate, args);
        if ("asBinder".equals(name) && method.getParameterCount() == 0
                && binderBoundary != null) return binderBoundary.binder();
        Object virtual = virtualResult(method, name, method.getReturnType(), args);
        boolean hostFeaturePassThrough = virtual == HostFeaturePassThrough.VALUE;
        if (!hostFeaturePassThrough && virtual != NoResult.VALUE) {
            return wrapResult(virtual, method.getReturnType(), name + ".virtual");
        }
        if (!hostFeaturePassThrough && isQueryMethod(name)) {
            return wrapResult(isolatedQueryDefault(method.getReturnType()), method.getReturnType(),
                    name + ".default");
        }

        Object[] rewritten = args == null ? null : args.clone();
        IdentityObjectRewriter.RewriteScope scope = IdentityObjectRewriter.rewriteArguments(rewritten, identity);
        try {
            try {
                Object[] binderArguments = binderBoundary == null ? rewritten
                        : binderBoundary.wrapArguments(rewritten, method.getParameterTypes(),
                                "packagemanager.callback");
                Object result = IdentityObjectRewriter.rewriteResult(
                        method.invoke(delegate, binderArguments), identity);
                return wrapResult(result, method.getReturnType(), name + ".return");
            }
            catch (InvocationTargetException error) { throw error.getCause(); }
        } finally {
            scope.close();
        }
    }

    private Object wrapResult(Object value, String role) {
        return wrapResult(value, null, role);
    }

    private Object wrapResult(Object value, Class<?> expectedType, String role) {
        return binderBoundary == null ? value
                : binderBoundary.wrapReturned(value, expectedType, role);
    }

    private Object virtualResult(Method method, String methodName, Class<?> returnType, Object[] args)
            throws Throwable {
        // The platform has overloads which carry the caller package/uid in addition to the
        // feature name. Those identity arguments describe the querying process, not a package
        // target. Resolve controlled features before the generic host-package deny-first check.
        Object signatureResult = virtualSignatureResult(methodName, args);
        if (signatureResult != NoResult.VALUE) return signatureResult;
        if (isSystemFeatureMethod(methodName)) {
            Object feature = virtualSystemFeature(args);
            if (feature != NoResult.VALUE) return feature;
        }
        Object permissionProjection = virtualPermissionResult(method, methodName, returnType, args);
        if (permissionProjection != NoResult.VALUE) return permissionProjection;
        VisibilityDecision decision = visibilityDecision(methodName, returnType, args);
        if (decision.result != NoResult.VALUE) return decision.result;
        String targetPackage = decision.targetPackage;
        boolean guestTarget = decision.guestTarget;
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
            case "getApplicationEnabledSetting":
            case "getApplicationEnabledSettingAsUser":
                if (!guestTarget) return hiddenPackageResult(methodName, returnType);
                VirtualPackageMetadata enabledTarget = identity.packageName().equals(targetPackage)
                        ? metadata : universe.packageMetadata(targetPackage);
                return enabledTarget == null ? hiddenPackageResult(methodName, returnType)
                        : enabledTarget.applicationEnabledSetting();
            case "getInstallerPackageName":
                if (!guestTarget) return null;
                VirtualPackageMetadata installerTarget = universe.packageMetadata(targetPackage);
                return installerTarget == null ? null : installerTarget.installerPackageName();
            case "getInstallSourceInfo":
                if (!guestTarget) return null;
                VirtualPackageMetadata sourceTarget = identity.packageName().equals(targetPackage)
                        ? metadata : universe.packageMetadata(targetPackage);
                return installSourceInfo(returnType, sourceTarget);
            case "checkPermission":
                if (!guestTarget) return PackageManager.PERMISSION_DENIED;
                String permission = firstString(args);
                // checkPermission(permission, packageName) asks about the target package.  Only
                // the current Guest can change while this process is running; peer packages use
                // their immutable virtual-PMS projection instead of inheriting our grant state.
                return identity.packageName().equals(targetPackage)
                        ? (identity.permissionPolicy().isGranted(permission)
                        ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED)
                        : universe.checkPermission(identity.packageName(), targetPackage, permission);
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
            case "activitySupportsIntent":
                return universe.activitySupportsIntent(identity.packageName(), firstComponent(args),
                        firstIntent(args), firstLong(args, 0L));
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
                return universe.provider(identity.packageName(), authority, firstLong(args, 0L),
                        identity.permissionPolicy().effectiveGrants());
            }
            case "resolveIntent":
            case "resolveActivity": {
                Intent intent = firstIntent(args);
                return universe.resolve(identity.packageName(), intent,
                        VirtualPackageMetadata.Type.ACTIVITY, firstLong(args, 0L),
                        identity.permissionPolicy().effectiveGrants());
            }
            case "resolveService": {
                Intent intent = firstIntent(args);
                return universe.resolve(identity.packageName(), intent,
                        VirtualPackageMetadata.Type.SERVICE, firstLong(args, 0L),
                        identity.permissionPolicy().effectiveGrants());
            }
            case "queryIntentActivities":
            case "queryIntentActivitiesAsUser":
                return query(returnType, args, VirtualPackageMetadata.Type.ACTIVITY);
            case "queryIntentActivityOptions":
                return queryActivityOptions(returnType, args);
            case "queryIntentReceivers":
            case "queryIntentReceiversAsUser":
            case "queryBroadcastReceivers":
            case "queryBroadcastReceiversAsUser":
                return query(returnType, args, VirtualPackageMetadata.Type.RECEIVER);
            case "queryIntentServices":
            case "queryIntentServicesAsUser":
                return query(returnType, args, VirtualPackageMetadata.Type.SERVICE);
            case "queryIntentContentProviders":
            case "queryIntentContentProvidersAsUser":
                return query(returnType, args, VirtualPackageMetadata.Type.PROVIDER);
            case "getInstalledPackages":
            case "getInstalledPackagesAsUser":
                return metadata.adaptCollection(universe.installedPackages(identity.packageName(),
                        firstLong(args, ~0L)), returnType);
            case "getPackagesHoldingPermissions":
            case "getPackagesHoldingPermissionsAsUser":
                return metadata.adaptCollection(universe.packagesHoldingPermissions(identity.packageName(),
                                firstStringArray(args), firstLong(args, 0x1000L)), returnType);
            case "getInstalledApplications":
            case "getInstalledApplicationsAsUser": {
                long flags = firstLong(args, 0L);
                return metadata.adaptCollection(universe.installedApplications(identity.packageName(), flags),
                        returnType);
            }
            case "getPackageGids":
            case "getPackageGidsEtc":
                return new int[0];
            case "queryContentProviders": {
                String processName = firstString(args);
                int uid = queryContentProviderUid(args);
                long flags = queryContentProviderFlags(args);
                return metadata.adaptCollection(universe.queryContentProviders(identity.packageName(),
                                processName, uid, flags,
                                identity.permissionPolicy().effectiveGrants()),
                        returnType);
            }
            case "getComponentEnabledSetting": {
                ComponentName component = firstComponent(args);
                if (component == null) throw new IllegalArgumentException("VIRTUAL_COMPONENT_REQUIRED");
                if (!universe.isVisibleTo(identity.packageName(), component.getPackageName())) {
                    return hiddenPackageResult(methodName, returnType);
                }
                return universe.componentEnabledSetting(identity.packageName(), component);
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
                if (guestTarget) {
                    // Package visibility is a read edge, not a capability to mutate a peer's
                    // manifest state. Keep the write package-owned like Android PMS.
                    throw new SecurityException("COMPONENT_STATE_FOREIGN_PACKAGE");
                }
                return NoResult.VALUE;
            }
            case "hasSigningCertificate":
                if (!guestTarget) return NoResult.VALUE;
                // The first argument is the package whose certificate is being queried.  The
                // previous implementation always compared against the current Guest's
                // certificate, so a visible cross-package package could be reported as signed
                // by the caller even when its own signature differed.  Keep the lookup inside
                // the virtual visibility graph and compare against the selected package's
                // immutable signature state.
                String signingPackage = targetPackageName(args);
                VirtualPackageMetadata signingMetadata = signingPackage.isEmpty()
                        || identity.packageName().equals(signingPackage)
                        ? metadata : universe.packageMetadata(signingPackage);
                if (signingMetadata == null
                        || !universe.isVisibleTo(identity.packageName(), signingMetadata.packageName())) {
                    return false;
                }
                return signingDigestMatches(args, signingMetadata.signatureSha256());
            case "getChangedPackages":
                // Sequence numbers are virtual-package-local. A host ChangedPackages object
                // would leak host-installed package names.
                return null;
            case "canPackageQuery": {
                String source = firstString(args);
                String target = secondString(args);
                if (source.isEmpty() || target.isEmpty()) return false;
                if (!identity.packageName().equals(source)) return false;
                return universe.isVisibleTo(source, target);
            }
            default:
                return NoResult.VALUE;
        }
    }

    /** Projects custom permissions/groups before the host PMS can answer with Host identity. */
    private Object virtualPermissionResult(Method method, String methodName, Class<?> returnType,
                                           Object[] args) {
        if ("getPermissionInfo".equals(methodName)) {
            String name = firstString(args);
            Object result = universe.permissionInfo(identity.packageName(), name, firstLong(args, 0L));
            if (result != null) return result;
            return isPlatformPermissionName(name) ? HostFeaturePassThrough.VALUE : null;
        }
        if ("queryPermissionsByGroup".equals(methodName)) {
            String group = firstString(args);
            List<Object> values = universe.queryPermissionsByGroup(identity.packageName(), group,
                    firstLong(args, 0L));
            if (isPlatformPermissionGroupName(group)) {
                return metadata.adaptCollection(
                        mergeHostPlatformPermissions(method, args, group, values), returnType);
            }
            return metadata.adaptCollection(values, returnType);
        }
        if ("getPermissionGroupInfo".equals(methodName)) {
            String name = firstString(args);
            Object result = universe.permissionGroupInfo(identity.packageName(), name,
                    firstLong(args, 0L));
            if (result != null) return result;
            return isPlatformPermissionGroupName(name) ? HostFeaturePassThrough.VALUE : null;
        }
        if ("getAllPermissionGroups".equals(methodName)) {
            List<Object> values = universe.permissionGroupInfos(identity.packageName(),
                    firstLong(args, 0L));
            // Keep the real platform catalog visible while projecting virtual declarations.
            // Returning the virtual list alone makes SDKs that enumerate standard groups (for
            // example CONTACTS/LOCATION) believe the device has no such permissions. Only
            // platform-namespaced host groups cross this boundary; arbitrary host/OEM package
            // groups remain hidden by the virtual package policy.
            return metadata.adaptCollection(
                    mergeHostPlatformPermissionGroups(method, args, values), returnType);
        }
        return NoResult.VALUE;
    }

    private List<Object> mergeHostPlatformPermissions(Method method, Object[] args, String group,
                                                      List<Object> virtualValues) {
        ArrayList<Object> merged = new ArrayList<>(virtualValues);
        Set<String> names = new LinkedHashSet<>();
        for (Object value : virtualValues) {
            if (value instanceof android.content.pm.PermissionInfo info && info.name != null) {
                names.add(info.name);
            }
        }
        try {
            Object raw = method.invoke(delegate, args);
            for (Object value : collectionValues(raw)) {
                if (!(value instanceof android.content.pm.PermissionInfo info)
                        || !isPlatformPermissionName(info.name)
                        // queryPermissionsByGroup() is an exact group query. A platform
                        // permission without a group, or one assigned to another group, must
                        // not leak into the requested virtual group merely because its name is
                        // in the android.permission namespace.
                        || !group.equals(info.group)
                        || !names.add(info.name)) continue;
                merged.add(info);
            }
        } catch (InvocationTargetException ignored) {
            // A platform without a readable permission catalog still has a valid virtual result.
        } catch (ReflectiveOperationException ignored) {
            // OEM IPackageManager variants may not expose a public list accessor.
        }
        return merged;
    }

    private static boolean isPlatformPermissionName(String value) {
        return value != null && value.startsWith("android.permission.");
    }

    private static boolean isPlatformPermissionGroupName(String value) {
        return value != null && value.startsWith("android.permission-group.");
    }

    private List<Object> mergeHostPlatformPermissionGroups(Method method, Object[] args,
                                                            List<Object> virtualValues) {
        ArrayList<Object> merged = new ArrayList<>(virtualValues);
        Set<String> names = new LinkedHashSet<>();
        for (Object value : virtualValues) {
            if (value instanceof PermissionGroupInfo info && info.name != null) {
                names.add(info.name);
            }
        }
        try {
            Object raw = method.invoke(delegate, args);
            for (Object value : collectionValues(raw)) {
                if (!(value instanceof PermissionGroupInfo info)
                        || !isPlatformPermissionGroupName(info.name)
                        || !names.add(info.name)) continue;
                merged.add(info);
            }
        } catch (InvocationTargetException ignored) {
            // A platform without a readable permission-group catalog still has a valid virtual
            // catalog. Keep the virtual result rather than turning a package query into failure.
        } catch (ReflectiveOperationException ignored) {
            // OEM IPackageManager variants may not expose a public list accessor.
        }
        return merged;
    }

    private static List<Object> collectionValues(Object value) {
        if (value == null) return Collections.emptyList();
        if (value instanceof List<?> list) return new ArrayList<>(list);
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            ArrayList<Object> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) result.add(Array.get(value, i));
            return result;
        }
        try {
            Method getList = value.getClass().getMethod("getList");
            Object list = getList.invoke(value);
            return list instanceof List<?> values ? new ArrayList<>(values)
                    : Collections.emptyList();
        } catch (ReflectiveOperationException ignored) {
            return Collections.emptyList();
        }
    }

    /** Keeps signature checks virtual without exposing Host PMS identity or certificate state. */
    private Object virtualSignatureResult(String methodName, Object[] args) {
        if (!"checkSignatures".equals(methodName) && !"checkUidSignatures".equals(methodName)) {
            return NoResult.VALUE;
        }
        if ("checkSignatures".equals(methodName)) {
            java.util.ArrayList<String> packages = new java.util.ArrayList<>();
            if (args != null) {
                for (Object arg : args) if (arg instanceof String value && !value.trim().isEmpty()) {
                    packages.add(value.trim());
                }
            }
            if (packages.size() < 2) return VirtualPackageUniverse.SIGNATURE_UNKNOWN_PACKAGE;
            return universe.checkSignatures(identity.packageName(), packages.get(0), packages.get(1));
        }
        java.util.ArrayList<Integer> uids = new java.util.ArrayList<>();
        if (args != null) {
            for (Object arg : args) if (arg instanceof Integer value) uids.add(value);
        }
        if (uids.size() < 2) return VirtualPackageUniverse.SIGNATURE_UNKNOWN_PACKAGE;
        return universe.checkUidSignatures(identity.packageName(), uids.get(0), uids.get(1));
    }

    /** Resolves package visibility before any virtual PMS result is projected. */
    private VisibilityDecision visibilityDecision(String methodName, Class<?> returnType,
                                                  Object[] args) {
        String targetPackage = ("resolveContentProvider".equals(methodName)
                // queryContentProviders() starts with a process name. A process name may look
                // like a package (guest.pkg:provider), but it is not a PackageManager identity
                // target and must not be classified as a hidden Host package.
                || "queryContentProviders".equals(methodName)) ? ""
                : targetPackageName(methodName, args);
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
            return new VisibilityDecision(targetPackage, false, null);
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
            return new VisibilityDecision(targetPackage, false,
                    hiddenPackageResult(methodName, returnType));
        }
        if (visibility != null && !guestTarget
                && PackageVisibilityPolicy.deniesIdentity(visibility)
                && !isSystemFeatureMethod(methodName)) {
            android.util.Log.w("CS_PM_HIDDEN_BLOCK", "method=" + methodName
                    + " class=" + visibility + " package=" + targetPackage
                    + " first=" + firstString(args), null);
            return new VisibilityDecision(targetPackage, false,
                    hiddenPackageResult(methodName, returnType));
        }
        if (containsHiddenPackage(args) && !guestTarget && !isSystemFeatureMethod(methodName)) {
            android.util.Log.w("CS_PM_HIDDEN_BLOCK", "method=" + methodName
                    + " package=" + packageArgument(args) + " first=" + firstString(args), null);
            return new VisibilityDecision(targetPackage, false,
                    hiddenPackageResult(methodName, returnType));
        }
        return new VisibilityDecision(targetPackage, guestTarget, NoResult.VALUE);
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
                || "getApplicationEnabledSetting".equals(methodName)
                || "getApplicationEnabledSettingAsUser".equals(methodName)
                || "getActivityInfo".equals(methodName)
                || "getReceiverInfo".equals(methodName)
                || "getServiceInfo".equals(methodName)
                || "getProviderInfo".equals(methodName)
                || "getComponentEnabledSetting".equals(methodName)
                || "setComponentEnabledSetting".equals(methodName)
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
            copyField(packageInfo, projected, "permissions");
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
                firstLong(args, 0L), identity.permissionPolicy().effectiveGrants());
        return metadata.adaptCollection(matches, returnType);
    }

    /**
     * Mirrors PackageManager.queryIntentActivityOptions() inside the same virtual resolver used
     * by ordinary activity queries. The platform gives each specific intent one preferred result,
     * then appends the remaining generic results. Resolving every option through the universe is
     * important: an explicit cross-package option must still obey <queries>, exported and
     * component-permission rules instead of leaking a Host ResolveInfo.
     */
    private Object queryActivityOptions(Class<?> returnType, Object[] args) {
        long flags = firstLong(args, 0L);
        List<Intent> specifics = specificIntents(args);
        Intent generic = firstIntent(args);
        java.util.ArrayList<ResolveInfo> result = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (Intent specific : specifics) {
            List<ResolveInfo> matches = universe.query(identity.packageName(), specific,
                    VirtualPackageMetadata.Type.ACTIVITY, flags,
                    identity.permissionPolicy().effectiveGrants());
            if (!matches.isEmpty()) {
                ResolveInfo preferred = matches.get(0);
                if (seen.add(resolveInfoComponentName(preferred))) result.add(preferred);
            }
        }
        for (ResolveInfo match : universe.query(identity.packageName(), generic,
                VirtualPackageMetadata.Type.ACTIVITY, flags,
                identity.permissionPolicy().effectiveGrants())) {
            if (seen.add(resolveInfoComponentName(match))) result.add(match);
        }
        return metadata.adaptCollection(result, returnType);
    }

    private static List<Intent> specificIntents(Object[] args) {
        if (args == null) return Collections.emptyList();
        java.util.ArrayList<Intent> result = new java.util.ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof Intent[]) {
                for (Intent intent : (Intent[]) arg) if (intent != null) result.add(intent);
            } else if (arg instanceof List<?>) {
                for (Object item : (List<?>) arg) if (item instanceof Intent) result.add((Intent) item);
            }
        }
        return result;
    }

    private static String resolveInfoComponentName(ResolveInfo value) {
        if (value == null) return "";
        if (value.activityInfo != null) {
            return value.activityInfo.packageName + "/" + value.activityInfo.name;
        }
        return "";
    }

    /** IPackageManager.queryContentProviders(processName, uid, flags, userId) uses int flags. */
    private static long queryContentProviderFlags(Object[] args) {
        if (args != null && args.length > 2) {
            Object value = args[2];
            if (value instanceof Long) return (Long) value;
            if (value instanceof Integer) return ((Integer) value).longValue();
        }
        return firstLong(args, 0L);
    }

    /** The hidden provider-inventory contract keeps UID at slot one even when process is null. */
    private static int queryContentProviderUid(Object[] args) {
        if (args != null && args.length > 1 && args[1] instanceof Integer) {
            return (Integer) args[1];
        }
        return -1;
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

    private static String[] firstStringArray(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) if (arg instanceof String[]) return (String[]) arg;
        return null;
    }

    private Object installSourceInfo(Class<?> returnType, VirtualPackageMetadata sourceTarget) {
        if (returnType == null || returnType == Object.class || sourceTarget == null
                || sourceTarget.installerPackageName().isEmpty()) return null;
        for (java.lang.reflect.Constructor<?> constructor : returnType.getDeclaredConstructors()) {
            try {
                Class<?>[] types = constructor.getParameterTypes();
                Object[] values = new Object[types.length];
                int stringIndex = 0;
                for (int index = 0; index < types.length; index++) {
                    if (types[index] == String.class) {
                        values[index] = stringIndex++ == 0
                                ? sourceTarget.installerPackageName() : null;
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

    private static String secondString(Object[] args) {
        if (args == null) return "";
        int seen = 0;
        for (Object arg : args) {
            if (!(arg instanceof String)) continue;
            seen++;
            if (seen == 2) return (String) arg;
        }
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

    private String targetPackageName(String methodName, Object[] args) {
        if ("checkPermission".equals(methodName)) return checkPermissionTargetPackage(args);
        return targetPackageName(args);
    }

    /**
     * PackageManager's checkPermission signature starts with the permission name, which can
     * itself be fully-qualified.  Resolve the package argument by package-universe membership
     * rather than assuming the first dotted String is the target.
     */
    private String checkPermissionTargetPackage(Object[] args) {
        java.util.ArrayList<String> strings = new java.util.ArrayList<>();
        if (args != null) {
            for (Object arg : args) if (arg instanceof String value && !value.trim().isEmpty()) {
                String normalized = value.trim();
                strings.add(normalized);
                if (identity.packageName().equals(normalized)
                        || identity.hostPackageName().equals(normalized)
                        || universe.packageMetadata(normalized) != null) {
                    return normalized;
                }
            }
        }
        // Unknown packages still need the normal hidden/denied result.  In the platform API the
        // package is the second String argument, so use the last String only as a safe fallback;
        // this avoids treating a custom permission name as the package.
        return strings.size() < 2 ? "" : strings.get(strings.size() - 1);
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
            // Public hasSigningCertificate() callers normally pass the encoded certificate,
            // whereas a few framework/OEM paths pass the already-computed SHA-256 digest.
            // Accept both shapes, but never treat an arbitrary certificate as its own digest.
            if (expectedHex.equalsIgnoreCase(hex.toString())) return true;
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hashed = digest.digest(value);
                StringBuilder hashedHex = new StringBuilder(hashed.length * 2);
                for (byte item : hashed) {
                    hashedHex.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
                }
                return expectedHex.equalsIgnoreCase(hashedHex.toString());
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
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

    private static final class VisibilityDecision {
        final String targetPackage;
        final boolean guestTarget;
        final Object result;

        VisibilityDecision(String targetPackage, boolean guestTarget, Object result) {
            this.targetPackage = targetPackage;
            this.guestTarget = guestTarget;
            this.result = result;
        }
    }

    private enum NoResult { VALUE }
    private enum HostFeaturePassThrough { VALUE }
}
