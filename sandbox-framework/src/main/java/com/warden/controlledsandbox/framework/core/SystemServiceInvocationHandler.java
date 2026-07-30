package com.warden.controlledsandbox.framework.core;

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
import java.util.Set;

/** Common Binder-interface proxy with identity rewriting and virtual permission/AppOps/capability decisions. */
public final class SystemServiceInvocationHandler implements InvocationHandler {
    private final Object delegate;
    private final GuestIdentity identity;
    private final String serviceName;
    private final CapabilityServiceInterceptor capabilityInterceptor;
    private final VirtualSystemServiceInterceptor virtualServiceInterceptor;
    private final DeviceServiceInvocationInterceptor deviceServiceInterceptor;
    private final InteractionServiceInvocationInterceptor interactionInterceptor;
    private final NetworkServiceInvocationInterceptor networkInterceptor;
    private final ApplicationEnvironmentInvocationInterceptor applicationEnvironmentInterceptor;
    private final CompatibilityInvocationInterceptor compatibilityInterceptor;
    private final PolicyServicesInvocationInterceptor policyServicesInterceptor;

    SystemServiceInvocationHandler(Object delegate, GuestIdentity identity) {
        this(delegate, identity, "");
    }

    SystemServiceInvocationHandler(Object delegate, GuestIdentity identity, String serviceName) {
        this.delegate = delegate;
        this.identity = identity;
        this.serviceName = serviceName == null ? "" : serviceName;
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
        this.applicationEnvironmentInterceptor = Set.of("usermanager", "launcherapps", "shortcut",
                "appwidget", "usagestats", "content").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new ApplicationEnvironmentInvocationInterceptor(identity, this.serviceName) : null;
        this.compatibilityInterceptor = Set.of("webviewupdate", "deviceidentifiers", "gms", "oemidentifier").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new CompatibilityInvocationInterceptor(identity, this.serviceName) : null;
        this.policyServicesInterceptor = Set.of("devicepolicy", "accessibility", "autofill",
                "biometric", "fingerprint", "sensorprivacy", "power", "vibrator").contains(
                this.serviceName.toLowerCase(java.util.Locale.ROOT))
                ? new PolicyServicesInvocationInterceptor(identity, this.serviceName) : null;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return method.invoke(delegate, arguments);
        Object virtual = virtualDecision(method, arguments);
        if (virtual != NoResult.VALUE) return virtual;
        CapabilityServiceInterceptor.Call capabilityCall = capabilityInterceptor == null
                ? null : capabilityInterceptor.before(method, arguments);
        PolicyServicesInvocationInterceptor.Decision policyServicesCall = policyServicesInterceptor == null
                ? PolicyServicesInvocationInterceptor.Decision.passThrough()
                : policyServicesInterceptor.before(method, arguments);
        if (policyServicesCall.handled()) return policyServicesCall.result();
        CompatibilityInvocationInterceptor.Decision compatibilityCall = compatibilityInterceptor == null
                ? CompatibilityInvocationInterceptor.Decision.passThrough()
                : compatibilityInterceptor.before(method, arguments);
        if (compatibilityCall.handled()) return compatibilityCall.result();
        ApplicationEnvironmentInvocationInterceptor.Decision applicationEnvironmentCall =
                applicationEnvironmentInterceptor == null
                        ? ApplicationEnvironmentInvocationInterceptor.Decision.passThrough()
                        : applicationEnvironmentInterceptor.before(method, arguments);
        if (applicationEnvironmentCall.handled()) return applicationEnvironmentCall.result();
        NetworkServiceInvocationInterceptor.Decision networkCall = networkInterceptor == null
                ? NetworkServiceInvocationInterceptor.Decision.passThrough()
                : networkInterceptor.before(method, arguments);
        if (networkCall.handled()) return networkCall.result();
        DeviceServiceInvocationInterceptor.Decision deviceCall = deviceServiceInterceptor == null
                ? DeviceServiceInvocationInterceptor.Decision.passThrough()
                : deviceServiceInterceptor.before(method, arguments);
        if (deviceCall.handled()) {
            if (capabilityInterceptor != null) capabilityInterceptor.afterVirtualSuccess(capabilityCall, arguments);
            return deviceCall.result();
        }
        Object[] rewritten = arguments == null ? null : arguments.clone();
        InteractionServiceInvocationInterceptor.Call interactionCall = interactionInterceptor == null
                ? InteractionServiceInvocationInterceptor.Call.passThrough()
                : interactionInterceptor.before(method, rewritten);
        if (interactionCall.handled()) {
            try { return interactionCall.result(); }
            finally { interactionCall.close(); }
        }
        VirtualSystemServiceInterceptor.Call virtualCall = virtualServiceInterceptor == null
                ? VirtualSystemServiceInterceptor.Call.passThrough()
                : virtualServiceInterceptor.before(method, rewritten);
        if (virtualCall.handled()) {
            try { return virtualCall.result(); }
            finally { interactionCall.close(); }
        }
        if (virtualCall.direct()) {
            try { return interactionCall.after(virtualCall.invokeDirect(delegate, method)); }
            finally { virtualCall.close(); interactionCall.close(); }
        }
        IdentityObjectRewriter.RewriteScope scope = IdentityObjectRewriter.rewriteArguments(rewritten, identity);
        try {
            try {
                Object result = method.invoke(delegate, rewritten);
                if (capabilityInterceptor != null) {
                    capabilityInterceptor.afterSuccess(capabilityCall, delegate, rewritten, result);
                }
                Object identityRewritten = IdentityObjectRewriter.rewriteResult(result, identity);
                Object interactionRewritten = interactionCall.after(identityRewritten);
                return virtualCall.rewriteResult(interactionRewritten);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                interactionCall.onFailure();
                virtualCall.onFailure();
                if (capabilityInterceptor != null) capabilityInterceptor.afterFailure(capabilityCall, cause);
                throw cause;
            } catch (Throwable error) {
                interactionCall.onFailure();
                virtualCall.onFailure();
                if (capabilityInterceptor != null) capabilityInterceptor.afterFailure(capabilityCall, error);
                throw error;
            }
        } finally {
            scope.close();
            virtualCall.close();
            interactionCall.close();
        }
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
        if (!targetsGuest(arguments)) return NoResult.VALUE;
        String methodName = method.getName();
        if (!(methodName.contains("Operation") || methodName.contains("ProxyOp")
                || methodName.contains("OpNoThrow") || methodName.contains("ProxyOperation"))) {
            return NoResult.VALUE;
        }
        String opName = firstOperation(arguments);
        Class<?> result = method.getReturnType();
        if (methodName.startsWith("finish") && result == void.class) return null;
        int mode = identity.appOpsPolicy().modeCode(opName);
        if (result == int.class || result == Integer.class) return mode;
        if (result == boolean.class || result == Boolean.class) return mode == 0;
        throw new SecurityException("VIRTUAL_APPOPS_SIGNATURE_UNSUPPORTED:" + methodName);
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
                } catch (Throwable ignored) { }
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
