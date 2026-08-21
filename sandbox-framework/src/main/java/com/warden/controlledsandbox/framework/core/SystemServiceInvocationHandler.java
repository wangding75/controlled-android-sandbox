package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.contract.InvocationMethodMatcher;

import com.warden.controlledsandbox.framework.binder.BinderIdentity;
import com.warden.controlledsandbox.framework.binder.BinderInterceptionFoundation;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.IdentityObjectRewriter;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Common Binder-interface proxy with identity rewriting and virtual permission/AppOps/capability decisions. */
public final class SystemServiceInvocationHandler implements InvocationHandler {
    private final Object delegate;
    private final GuestIdentity identity;
    private final String serviceName;
    private final SystemServiceSemanticAdapter semanticAdapter;
    private final CapabilityServiceInterceptor capabilityInterceptor;
    private final VirtualSystemServiceInterceptor virtualServiceInterceptor;
    private final DeviceServiceInvocationInterceptor deviceServiceInterceptor;
    private final InteractionServiceInvocationInterceptor interactionInterceptor;
    private final NetworkServiceInvocationInterceptor networkInterceptor;
    private final ApplicationEnvironmentInvocationInterceptor applicationEnvironmentInterceptor;
    private final CompatibilityInvocationInterceptor compatibilityInterceptor;
    private final PolicyServicesInvocationInterceptor policyServicesInterceptor;
    private final MediaCommunicationInvocationInterceptor mediaCommunicationInterceptor;
    private final PeripheralServicesInvocationInterceptor peripheralServicesInterceptor;
    private final PrivilegedServicesInvocationInterceptor privilegedServicesInterceptor;
    private volatile BinderInterceptionFoundation binderBoundary;

    SystemServiceInvocationHandler(Object delegate, GuestIdentity identity) {
        this(delegate, identity, "");
    }

    SystemServiceInvocationHandler(Object delegate, GuestIdentity identity, String serviceName) {
        this.delegate = delegate;
        this.identity = identity;
        this.serviceName = serviceName == null ? "" : serviceName;
        this.semanticAdapter = new SystemServiceSemanticAdapter(identity, this.serviceName);
        this.capabilityInterceptor = Set.of("camera", "location", "audio").contains(this.serviceName)
                ? new CapabilityServiceInterceptor(identity, this.serviceName) : null;
        this.virtualServiceInterceptor = Set.of("alarm", "clipboard", "account", "notification", "jobscheduler")
                .contains(this.serviceName) ? new VirtualSystemServiceInterceptor(identity, this.serviceName) : null;
        this.deviceServiceInterceptor = Set.of("location", "telephony", "phonesubinfo",
                "telephonyregistry", "subscription", "wifi", "wifiscanner", "bluetooth", "sensor")
                .contains(this.serviceName) ? new DeviceServiceInvocationInterceptor(identity, this.serviceName) : null;
        this.interactionInterceptor = Set.of("window", "windowSession", "activityClient",
                "inputMethod", "display").stream().map(String::toLowerCase)
                .anyMatch(value -> value.equals(this.serviceName.toLowerCase(java.util.Locale.ROOT)))
                ? new InteractionServiceInvocationInterceptor(identity, this.serviceName, delegate) : null;
        this.networkInterceptor = Set.of("connectivity", "dnsresolver", "vpn").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new NetworkServiceInvocationInterceptor(identity, this.serviceName) : null;
        this.applicationEnvironmentInterceptor = Set.of("usermanager", "restrictions", "launcherapps",
                "shortcut", "appwidget", "usagestats", "content").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new ApplicationEnvironmentInvocationInterceptor(identity, this.serviceName) : null;
        this.compatibilityInterceptor = Set.of("webviewupdate", "deviceidentifiers", "gms", "oemidentifier").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new CompatibilityInvocationInterceptor(identity, this.serviceName) : null;
        this.policyServicesInterceptor = Set.of("devicepolicy", "accessibility", "autofill",
                "biometric", "fingerprint", "sensorprivacy", "power", "vibrator").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new PolicyServicesInvocationInterceptor(identity, this.serviceName) : null;
        this.mediaCommunicationInterceptor = Set.of("mediasession", "mediarouter", "audio",
                "isms", "isms2", "isms_msim", "backup", "dropbox").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new MediaCommunicationInvocationInterceptor(identity, this.serviceName) : null;
        this.peripheralServicesInterceptor = Set.of("nfc", "usb", "print",
                "companiondevice", "mediaprojection", "camera", "oemsystem").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new PeripheralServicesInvocationInterceptor(identity, this.serviceName) : null;
        this.privilegedServicesInterceptor = Set.of("search", "storagestats", "graphicsstats",
                "contexthub", "persistentdatablock", "systemupdate").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new PrivilegedServicesInvocationInterceptor(identity, this.serviceName) : null;
        this.binderBoundary = null;
    }

    /**
     * Attaches the single low-level Binder boundary after the typed proxy has been created.  The
     * local interface is intentionally the CAS proxy, never the Host IInterface, so a caller that
     * obtains the root Binder through ServiceManager cannot recover the Host implementation via
     * queryLocalInterface().
     */
    void attachBinderBoundary(Object localInterface) {
        if (binderBoundary != null || delegate == null || identity == null) return;
        if (!(delegate instanceof android.os.IInterface typed)) return;
        android.os.IBinder binder;
        try {
            binder = typed.asBinder();
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("BINDER_SERVICE_AS_BINDER_FAILED:" + serviceName, error);
        }
        if (binder == null) return;
        BinderIdentity binderIdentity = BinderIdentity.fromGuestIdentity(identity);
        binderBoundary = BinderInterceptionFoundation.builder(binder, binderIdentity)
                .serviceName(serviceName)
                .localInterface(localInterface)
                .sessionFence(identity.binderSessionFence())
                .preserveBinderType("android.app.IApplicationThread")
                .build();
    }

    void invalidateBinderBoundary(String reason) {
        BinderInterceptionFoundation boundary = binderBoundary;
        if (boundary != null) boundary.invalidate(reason);
    }

    BinderInterceptionFoundation binderBoundary() { return binderBoundary; }

    @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (serviceName.equals("telephony") || serviceName.equals("phonesubinfo")
                || serviceName.equals("subscription")) {
            android.util.Log.i("CS_TELEPHONY_PROXY", "service=" + serviceName
                    + " method=" + method.getName() + " return=" + method.getReturnType().getName());
        }
        if (method.getDeclaringClass() == Object.class) {
            if (delegate != null) return method.invoke(delegate, arguments);
            return syntheticObjectMethod(proxy, method, arguments);
        }
        if ("asBinder".equals(method.getName()) && method.getParameterCount() == 0
                && binderBoundary != null) {
            return binderBoundary.binder();
        }
        if (delegate == null && "asBinder".equals(method.getName())
                && method.getParameterCount() == 0) return null;
        Object activityManagerResult = activityManagerResult(method);
        if (activityManagerResult != NoResult.VALUE) {
            return wrapBoundaryResult(activityManagerResult, serviceName + ".activity.return");
        }
        Object virtual = virtualDecision(method, arguments);
        if (virtual != NoResult.VALUE) {
            return wrapBoundaryResult(virtual, serviceName + ".virtual.return");
        }
        CapabilityServiceInterceptor.Call capabilityCall = capabilityInterceptor == null
                ? null : capabilityInterceptor.before(method, arguments);
        PolicyServicesInvocationInterceptor.Decision policyServicesCall = policyServicesInterceptor == null
                ? PolicyServicesInvocationInterceptor.Decision.passThrough()
                : policyServicesInterceptor.before(method, arguments);
        if (policyServicesCall.handled()) {
            return wrapBoundaryResult(policyServicesCall.result(), serviceName + ".policy.return");
        }
        MediaCommunicationInvocationInterceptor.Decision mediaCommunicationCall =
                mediaCommunicationInterceptor == null
                        ? MediaCommunicationInvocationInterceptor.Decision.passThrough()
                        : mediaCommunicationInterceptor.before(method, arguments);
        if (mediaCommunicationCall.handled()) {
            return wrapBoundaryResult(mediaCommunicationCall.result(), serviceName + ".media.return");
        }
        PrivilegedServicesInvocationInterceptor.Decision privilegedCall =
                privilegedServicesInterceptor == null
                        ? PrivilegedServicesInvocationInterceptor.Decision.passThrough()
                        : privilegedServicesInterceptor.before(method, arguments);
        if (privilegedCall.handled()) {
            return wrapBoundaryResult(privilegedCall.result(), serviceName + ".privileged.return");
        }
        PeripheralServicesInvocationInterceptor.Decision peripheralCall =
                peripheralServicesInterceptor == null
                        ? PeripheralServicesInvocationInterceptor.Decision.passThrough()
                        : peripheralServicesInterceptor.before(method, arguments);
        if (peripheralCall.handled()) {
            if (capabilityInterceptor != null) {
                capabilityInterceptor.afterVirtualSuccess(capabilityCall, arguments);
            }
            return wrapBoundaryResult(peripheralCall.result(), serviceName + ".peripheral.return");
        }
        CompatibilityInvocationInterceptor.Decision compatibilityCall = compatibilityInterceptor == null
                ? CompatibilityInvocationInterceptor.Decision.passThrough()
                : compatibilityInterceptor.before(method, arguments);
        if (compatibilityCall.handled()) {
            return wrapBoundaryResult(compatibilityCall.result(), serviceName + ".compatibility.return");
        }
        ApplicationEnvironmentInvocationInterceptor.Decision applicationEnvironmentCall =
                applicationEnvironmentInterceptor == null
                        ? ApplicationEnvironmentInvocationInterceptor.Decision.passThrough()
                        : applicationEnvironmentInterceptor.before(method, arguments);
        if (applicationEnvironmentCall.handled()) {
            return wrapBoundaryResult(applicationEnvironmentCall.result(), serviceName + ".environment.return");
        }
        NetworkServiceInvocationInterceptor.Decision networkCall = networkInterceptor == null
                ? NetworkServiceInvocationInterceptor.Decision.passThrough()
                : networkInterceptor.before(method, arguments);
        if (networkCall.handled()) {
            return wrapBoundaryResult(networkCall.result(), serviceName + ".network.return");
        }
        DeviceServiceInvocationInterceptor.Decision deviceCall = deviceServiceInterceptor == null
                ? DeviceServiceInvocationInterceptor.Decision.passThrough()
                : deviceServiceInterceptor.before(method, arguments);
        if (deviceCall.handled()) {
            if (capabilityInterceptor != null) capabilityInterceptor.afterVirtualSuccess(capabilityCall, arguments);
            return wrapBoundaryResult(deviceCall.result(), serviceName + ".device.return");
        }
        Object[] rewritten = arguments == null ? null : arguments.clone();
        InteractionServiceInvocationInterceptor.Call interactionCall = interactionInterceptor == null
                ? InteractionServiceInvocationInterceptor.Call.passThrough()
                : interactionInterceptor.before(method, rewritten);
        if (interactionCall.handled()) {
            recordInteractionInvocation(method, false);
            try { return wrapBoundaryResult(interactionCall.result(), serviceName + ".interaction.return"); }
            finally { interactionCall.close(); }
        }
        VirtualSystemServiceInterceptor.Call virtualCall = virtualServiceInterceptor == null
                ? VirtualSystemServiceInterceptor.Call.passThrough()
                : virtualServiceInterceptor.before(method, rewritten);
        if (virtualCall.handled()) {
            try { return wrapBoundaryResult(virtualCall.result(), serviceName + ".virtual-adapter.return"); }
            finally { interactionCall.close(); }
        }
        if (virtualCall.direct()) {
            try {
                Object result = interactionCall.after(virtualCall.invokeDirect(delegate, method));
                return binderBoundary == null ? result
                        : binderBoundary.wrapReturned(result, method.getReturnType(),
                                serviceName + ".direct.return");
            }
            finally { virtualCall.close(); interactionCall.close(); }
        }
        IdentityObjectRewriter.RewriteScope scope = semanticAdapter.rewriteArguments(rewritten);
        try {
            try {
                if (delegate == null) {
                    throw new UnsupportedOperationException(
                            "VIRTUAL_" + serviceName.toUpperCase(java.util.Locale.ROOT)
                                    + "_SIGNATURE_UNSUPPORTED:" + method.getName());
                }
                recordInteractionInvocation(method, true);
                Object[] binderArguments = binderBoundary == null ? rewritten
                        : binderBoundary.wrapArguments(rewritten, method.getParameterTypes(),
                                serviceName + ".callback");
                Object result = method.invoke(delegate, binderArguments);
                if (capabilityInterceptor != null) {
                    capabilityInterceptor.afterSuccess(capabilityCall, delegate, rewritten, result);
                }
                Object identityRewritten = semanticAdapter.projectResult(result);
                Object interactionRewritten = interactionCall.after(identityRewritten);
                Object projected = virtualCall.rewriteResult(interactionRewritten);
                return binderBoundary == null ? projected
                        : binderBoundary.wrapReturned(projected, method.getReturnType(),
                                serviceName + ".return");
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                interactionCall.onFailure();
                virtualCall.onFailure();
                if (capabilityInterceptor != null) capabilityInterceptor.afterFailure(capabilityCall, cause);
                throw cause;
            } catch (Throwable error) {
                try {
                    interactionCall.onFailure();
                    virtualCall.onFailure();
                    if (capabilityInterceptor != null) capabilityInterceptor.afterFailure(capabilityCall, error);
                } finally {
                    com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
                }
                throw error;
            }
        } finally {
            scope.close();
            virtualCall.close();
            interactionCall.close();
        }
    }

    private Object activityManagerResult(Method method) {
        if (!isActivityManagerService(serviceName)
                || !"getHistoricalProcessExitReasons".equals(method.getName())) {
            return NoResult.VALUE;
        }
        // Android 16 protects this host-wide diagnostic history with DUMP. A Guest must not
        // read host process history; an empty Guest-owned history is the safe read-only view.
        android.util.Log.i("CS_ACTIVITY_MANAGER", "historicalProcessExitReasons=guest-empty");
        return emptyActivityManagerHistory(method.getReturnType());
    }

    private static boolean isActivityManagerService(String value) {
        if (value == null) return false;
        String normalized = value.replace("-", "")
                .toLowerCase(java.util.Locale.ROOT);
        return "activitymanager".equals(normalized);
    }

    private static Object emptyActivityManagerHistory(Class<?> returnType) {
        if (returnType == null || returnType == void.class) return null;
        if (List.class.isAssignableFrom(returnType)) return Collections.emptyList();
        if (returnType.isArray()) return Array.newInstance(returnType.getComponentType(), 0);
        if (returnType.getName().endsWith("ParceledListSlice")) {
            try {
                java.lang.reflect.Constructor<?> constructor =
                        returnType.getDeclaredConstructor(List.class);
                constructor.setAccessible(true);
                return constructor.newInstance(Collections.emptyList());
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("VIRTUAL_ACTIVITY_EXIT_HISTORY_PROJECTION_FAILED",
                        error);
            }
        }
        throw new SecurityException("VIRTUAL_ACTIVITY_EXIT_HISTORY_SIGNATURE_UNSUPPORTED:"
                + returnType.getName());
    }

    String serviceName() { return serviceName; }

    private Object wrapBoundaryResult(Object value, String role) {
        return binderBoundary == null ? value : binderBoundary.wrapReturned(value, role);
    }

    private void recordInteractionInvocation(Method method, boolean delegated) {
        if (interactionInterceptor == null || identity == null) return;
        try {
            identity.interactions().invocations().record(serviceName, method.getName(), delegated);
            android.util.Log.i("CS_INTERACTION_PROXY", "ACTUAL_PROXY_INVOCATION service="
                    + serviceName + " method=" + method.getName() + " path="
                    + (delegated ? "delegate" : "virtual"));
        } catch (Throwable diagnosticFailure) {
            // Invocation evidence is diagnostic only and must not change service semantics.
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(diagnosticFailure);
        }
    }

    private Object syntheticObjectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "toString" -> "SyntheticSystemService[" + serviceName + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (arguments == null ? null : arguments[0]);
            default -> null;
        };
    }

    private Object virtualDecision(Method method, Object[] arguments) {
        if ("permission".equals(serviceName)) return permissionDecision(method, arguments);
        if ("appops".equals(serviceName)) return appOpsDecision(method, arguments);
        return NoResult.VALUE;
    }

    private Object permissionDecision(Method method, Object[] arguments) {
        String name = method.getName();
        if (!targetsGuest(arguments)) return NoResult.VALUE;
        if (isPermissionMutation(name)) {
            throw new SecurityException("VIRTUAL_PERMISSION_MUTATION_REQUIRES_PACKAGE_SERVICE");
        }
        String permission = firstPermission(arguments);
        if (permission.isEmpty()) return NoResult.VALUE;
        boolean granted = identity.permissionPolicy().isGranted(permission);
        Class<?> result = method.getReturnType();
        if (isPermissionCheck(name)) {
            if (result == int.class || result == Integer.class) return granted ? 0 : -1;
            if (result == boolean.class || result == Boolean.class) return granted;
            throw new SecurityException("VIRTUAL_PERMISSION_SIGNATURE_UNSUPPORTED:" + name);
        }
        if (name.contains("RevokedByPolicy") && (result == boolean.class || result == Boolean.class)) {
            return !granted;
        }
        return NoResult.VALUE;
    }

    private static boolean isPermissionMutation(String name) {
        return name.startsWith("grant") || name.startsWith("revoke")
                || name.startsWith("updatePermission") || name.startsWith("resetRuntimePermissions");
    }

    private static boolean isPermissionCheck(String name) {
        return name.startsWith("check") && name.toLowerCase(java.util.Locale.ROOT).contains("permission");
    }

    private Object appOpsDecision(Method method, Object[] arguments) {
        String methodName = method.getName();
        if (semanticAdapter.containsHostAttribution(arguments)) {
            // A Guest must never be able to smuggle the Host opPackageName or an already-host
            // AttributionSource into the framework service.  The normal outbound transform is
            // Guest -> Host and therefore only runs after this validation point.
            throw new SecurityException("VIRTUAL_APPOPS_HOST_ATTRIBUTION_HIDDEN");
        }
        if ("checkPackage".equals(methodName)) {
            return appOpsCheckPackage(method, arguments);
        }
        if (isAppOpsMutation(methodName)) {
            // AppOps state is Package-Service-owned. A Guest-side IAppOpsService proxy must
            // never mutate the Host table, even when the call carries only a numeric UID and
            // therefore cannot be recognized by the ordinary package/attribution matcher.
            throw new SecurityException("VIRTUAL_APPOPS_MUTATION_REQUIRES_PACKAGE_SERVICE:"
                    + methodName);
        }
        if (isAppOpsInventory(methodName)) {
            // These APIs return host package/UID operation records. Returning an empty virtual
            // inventory is the only safe projection until a typed per-Guest OpEntry model is
            // available; delegating would expose Host package names and historical timestamps.
            if ("getPackagesForOps".equals(methodName)
                    || InvocationMethodMatcher.startsWith(methodName, "getHistorical")) {
                return emptyAppOpsResult(method.getReturnType());
            }
            if (!targetsGuest(arguments)) return NoResult.VALUE;
            return emptyAppOpsResult(method.getReturnType());
        }
        if (!targetsGuest(arguments)) return NoResult.VALUE;
        if (!(InvocationMethodMatcher.containsAny(methodName,
                "Operation", "ProxyOp", "OpNoThrow", "ProxyOperation"))) {
            return NoResult.VALUE;
        }
        String opName = firstOperation(arguments);
        Class<?> result = method.getReturnType();
        if (InvocationMethodMatcher.startsWith(methodName, "finish") && result == void.class) return null;
        int mode = identity.appOpsPolicy().modeCode(opName);
        if (result == int.class || result == Integer.class) return mode;
        if (result == boolean.class || result == Boolean.class) return mode == 0;
        // Android 11+ returns a SyncNotedAppOp from noteOperation/noteProxyOperation and
        // AppOpsManager converts that value back to the public mode. Preserve the framework
        // contract instead of rejecting the operation merely because the Binder return type
        // changed from int to a structured value.
        if ("android.app.SyncNotedAppOp".equals(result.getName())) {
            try {
                java.lang.reflect.Constructor<?> constructor =
                        result.getDeclaredConstructor(int.class, String.class);
                constructor.setAccessible(true);
                return constructor.newInstance(mode, firstAttributionTag(arguments));
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                        .rethrowIfFatal(error);
                throw new IllegalStateException("VIRTUAL_APPOPS_SYNC_NOTED_APP_OP_FAILED", error);
            }
        }
        throw new SecurityException("VIRTUAL_APPOPS_SIGNATURE_UNSUPPORTED:" + methodName);
    }

    private Object appOpsCheckPackage(Method method, Object[] arguments) {
        String packageName = firstPackageName(arguments);
        Integer uid = firstInteger(arguments);
        boolean guestPackage = identity.packageName().equals(packageName);
        boolean guestUid = uid != null && uid == identity.virtualUid();
        if (guestPackage && (uid == null || guestUid)) {
            // IAppOpsService.checkPackage() is void on Android; preserve a false/zero fallback
            // for reduced API stubs without manufacturing a Host PackageOps result.
            return defaultValue(method.getReturnType());
        }
        if (guestPackage || guestUid) {
            throw new SecurityException("VIRTUAL_APPOPS_PACKAGE_UID_MISMATCH");
        }
        if (identity.hostPackageName().equals(packageName)) {
            throw new SecurityException("VIRTUAL_APPOPS_HOST_PACKAGE_HIDDEN");
        }
        return NoResult.VALUE;
    }

    private static boolean isAppOpsMutation(String methodName) {
        return InvocationMethodMatcher.named(methodName,
                "setMode", "setUidMode", "resetAllModes", "clearHistory",
                "setHistoryParameters", "removePackage", "packageRemoved");
    }

    private static boolean isAppOpsInventory(String methodName) {
        return InvocationMethodMatcher.named(methodName,
                "getOpsForPackage", "getUidOps", "getPackagesForOps",
                "getHistoricalOps", "getHistoricalOpsFromDisk", "getNonPackageUidOps");
    }

    private static Object emptyAppOpsResult(Class<?> returnType) {
        if (returnType == null || returnType == void.class) return null;
        if (java.util.List.class.isAssignableFrom(returnType)) return Collections.emptyList();
        if (java.util.Set.class.isAssignableFrom(returnType)) return Collections.emptySet();
        if (returnType.isArray()) return Array.newInstance(returnType.getComponentType(), 0);
        return defaultValue(returnType);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == null || returnType == void.class) return null;
        if (returnType == boolean.class || returnType == Boolean.class) return false;
        if (returnType == byte.class || returnType == Byte.class) return (byte) 0;
        if (returnType == short.class || returnType == Short.class) return (short) 0;
        if (returnType == int.class || returnType == Integer.class) return 0;
        if (returnType == long.class || returnType == Long.class) return 0L;
        if (returnType == float.class || returnType == Float.class) return 0f;
        if (returnType == double.class || returnType == Double.class) return 0d;
        if (returnType == char.class || returnType == Character.class) return '\0';
        if (returnType.isArray()) return Array.newInstance(returnType.getComponentType(), 0);
        if (java.util.List.class.isAssignableFrom(returnType)) return Collections.emptyList();
        if (java.util.Set.class.isAssignableFrom(returnType)) return Collections.emptySet();
        return null;
    }

    private static String firstPackageName(Object[] arguments) {
        if (arguments == null) return "";
        for (Object argument : arguments) {
            if (argument instanceof String value && value.contains(".")) return value;
        }
        return "";
    }

    private static Integer firstInteger(Object[] arguments) {
        if (arguments == null) return null;
        for (Object argument : arguments) if (argument instanceof Integer value) return value;
        return null;
    }

    private String firstAttributionTag(Object[] arguments) {
        if (arguments == null) return null;
        for (Object argument : arguments) {
            if (!(argument instanceof String value)) continue;
            if (identity.packageName().equals(value) || identity.hostPackageName().equals(value)) continue;
            if (value.startsWith("android:") || identity.appOpsPolicy().modes().containsKey(value)) continue;
            return value;
        }
        return null;
    }

    private boolean targetsGuest(Object[] arguments) {
        if (arguments == null) return false;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object argument : arguments) {
            if (matchesGuest(argument, visited, 0)) return true;
        }
        return false;
    }

    private boolean matchesGuest(Object value, Set<Object> visited, int depth) {
        if (value == null || depth > 8) return false;
        if (identity.packageName().equals(value)) return true;
        if (value instanceof Integer && ((Integer) value) == identity.virtualUid()) return true;
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value.getClass().isEnum()) return false;
        if (!visited.add(value)) return false;
        Class<?> type = value.getClass();
        if (type.isArray() && !type.getComponentType().isPrimitive()) {
            int length = Math.min(Array.getLength(value), 64);
            for (int index = 0; index < length; index++) {
                if (matchesGuest(Array.get(value, index), visited, depth + 1)) return true;
            }
            return false;
        }
        String typeName = type.getName();
        if (!typeName.contains("Attribution")) return false;
        Class<?> cursor = type;
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                String name = field.getName();
                boolean identityField = name.equals("mPackageName") || name.equals("packageName")
                        || name.equals("mUid") || name.equals("uid");
                boolean attributionField = name.equals("mNext") || name.equals("next")
                        || field.getType().getName().contains("Attribution");
                if (!identityField && !attributionField) continue;
                try {
                    field.setAccessible(true);
                    if (matchesGuest(field.get(value), visited, depth + 1)) return true;
                } catch (Throwable ignored) { com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(ignored); }
            }
            cursor = cursor.getSuperclass();
        }
        return false;
    }

    private String firstPermission(Object[] arguments) {
        if (arguments == null) return "";
        for (Object argument : arguments) {
            if (!(argument instanceof String)) continue;
            String value = (String) argument;
            if (identity.packageName().equals(value) || identity.hostPackageName().equals(value)) continue;
            if (value.contains("permission") || identity.permissionPolicy().declaredPermissions().contains(value)) {
                return value;
            }
        }
        return "";
    }

    private String firstOperation(Object[] arguments) {
        if (arguments == null) return "android:unknown_op";
        for (Object argument : arguments) {
            if (argument instanceof Integer) {
                return SandboxAppOpsPolicy.operationName((Integer) argument);
            }
            if (!(argument instanceof String)) continue;
            String value = (String) argument;
            if (identity.packageName().equals(value) || identity.hostPackageName().equals(value)) continue;
            if (value.startsWith("android:") || identity.appOpsPolicy().modes().containsKey(value)) return value;
        }
        return "android:unknown_op";
    }

    private enum NoResult { VALUE }
}
