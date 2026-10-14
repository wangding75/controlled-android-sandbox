package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.contract.InvocationMethodMatcher;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Intercepts Xiaomi/HyperOS miui.security.SecurityManager Binder calls.
 *
 * <p>Returns safe values that allow a guest process to operate without restrictions:</p>
 * <ul>
 *   <li>isAllowStartActivity → true</li>
 *   <li>checkAccessControl → true (access granted)</li>
 *   <li>getAppRunningControlEnabled → false (control disabled)</li>
 *   <li>actuallyCheckPermission → 0 (PERMISSION_GRANTED)</li>
 *   <li>checkSmsBlocked → false</li>
 *   <li>isValidDevice → true</li>
 *   <li>isGameMode → false</li>
 *   <li>isTrackWakeUp → false</li>
 *   <li>isAppPermissionControlOpen → false</li>
 *   <li>boolean mutations (set*) → true (acknowledged)</li>
 *   <li>getAppPrivacyStatus → 0</li>
 *   <li>getWakeUpTime / getCurrentUserId → 0L / 0</li>
 * </ul>
 */
final class MiuiSecurityInvocationInterceptor {
    record Decision(boolean handled, Object result) {
        static Decision passThrough() { return new Decision(false, null); }
        static Decision handled(Object value) { return new Decision(true, value); }
    }

    private final String serviceName;

    MiuiSecurityInvocationInterceptor(String serviceName) {
        this.serviceName = serviceName == null ? "" : serviceName.toLowerCase(Locale.ROOT);
    }

    Decision before(Method method, Object[] args) {
        if (!"miuisecurity".equals(serviceName)) return Decision.passThrough();
        String name = method.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        Class<?> ret = method.getReturnType();

        // ---- boolean checks that gate activity starts / access control ----
        if (InvocationMethodMatcher.named(name, "isAllowStartActivity",
                "checkAccessControl", "isValidDevice")) {
            return Decision.handled(boolDefault(ret, true));
        }
        // ---- boolean checks that should report "disabled" for guest safety ----
        if (InvocationMethodMatcher.named(name, "getAppRunningControlEnabled",
                "isGameMode", "isTrackWakeUp", "isAppPermissionControlOpen",
                "checkSmsBlocked")) {
            return Decision.handled(boolDefault(ret, false));
        }
        // ---- mutation / set* → acknowledge success ----
        if (InvocationMethodMatcher.startsWith(lower, "set")
                && (ret == void.class || ret == boolean.class || ret == Boolean.class)) {
            return Decision.handled(ret == void.class ? null : true);
        }
        if (InvocationMethodMatcher.named(name,
                "setAppRunningControlEnabled", "pushNewNotification", "saveIcon")) {
            return Decision.handled(boolDefault(ret, true));
        }
        // ---- numeric queries ----
        if (InvocationMethodMatcher.named(name, "actuallyCheckPermission",
                "getAppPrivacyStatus", "getCurrentUserId")) {
            if (ret == int.class || ret == Integer.class) return Decision.handled(0);
            if (ret == long.class || ret == Long.class) return Decision.handled(0L);
            return Decision.handled(null);
        }
        if (InvocationMethodMatcher.named(name, "getWakeUpTime")) {
            if (ret == long.class || ret == Long.class) return Decision.handled(0L);
            if (ret == int.class || ret == Integer.class) return Decision.handled(0);
            return Decision.handled(null);
        }
        // ---- null/empty queries ----
        if (InvocationMethodMatcher.named(name, "getIcon")) {
            return Decision.handled(null);
        }
        // ---- catch-all: return type-appropriate default so the guest never throws ----
        if (ret == void.class) return Decision.handled(null);
        if (ret == boolean.class || ret == Boolean.class) return Decision.handled(false);
        if (ret == int.class || ret == Integer.class) return Decision.handled(0);
        if (ret == long.class || ret == Long.class) return Decision.handled(0L);
        if (ret == String.class) return Decision.handled("");
        return Decision.handled(null);
    }

    private static Object boolDefault(Class<?> ret, boolean value) {
        if (ret == void.class) return null;
        if (ret == boolean.class || ret == Boolean.class) return value;
        if (ret == int.class || ret == Integer.class) return value ? 1 : 0;
        return null;
    }
}
