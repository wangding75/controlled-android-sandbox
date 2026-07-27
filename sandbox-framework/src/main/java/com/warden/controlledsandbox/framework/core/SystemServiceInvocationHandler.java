package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.IdentityObjectRewriter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Common Binder-interface proxy with identity rewriting and virtual permission/AppOps decisions. */
public final class SystemServiceInvocationHandler implements InvocationHandler {
    private final Object delegate;
    private final GuestIdentity identity;
    private final String serviceName;

    SystemServiceInvocationHandler(Object delegate, GuestIdentity identity) {
        this(delegate, identity, "");
    }

    SystemServiceInvocationHandler(Object delegate, GuestIdentity identity, String serviceName) {
        this.delegate = delegate;
        this.identity = identity;
        this.serviceName = serviceName == null ? "" : serviceName;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return method.invoke(delegate, arguments);
        Object virtual = virtualDecision(method, arguments);
        if (virtual != NoResult.VALUE) return virtual;
        Object[] rewritten = arguments == null ? null : arguments.clone();
        IdentityObjectRewriter.RewriteScope scope = IdentityObjectRewriter.rewriteArguments(rewritten, identity);
        try {
            try {
                Object result = method.invoke(delegate, rewritten);
                return IdentityObjectRewriter.rewriteResult(result, identity);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        } finally {
            scope.close();
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
                || methodName.contains("OpNoThrow"))) return NoResult.VALUE;
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
        for (Object argument : arguments) {
            if (identity.packageName().equals(argument)) return true;
            if (argument instanceof Integer && ((Integer) argument) == identity.virtualUid()) return true;
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
        if (arguments == null) return "";
        for (Object argument : arguments) {
            if (!(argument instanceof String)) continue;
            String value = (String) argument;
            if (identity.packageName().equals(value) || identity.hostPackageName().equals(value)) continue;
            if (value.startsWith("android:") || identity.appOpsPolicy().modes().containsKey(value)) return value;
        }
        return "";
    }

    private enum NoResult { VALUE }
}
