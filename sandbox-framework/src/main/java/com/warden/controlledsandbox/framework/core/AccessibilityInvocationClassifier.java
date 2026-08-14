package com.warden.controlledsandbox.framework.core;

import java.util.Locale;

/** Maps IAccessibilityManager method names onto {@link AccessibilityInvocationClass}. */
public final class AccessibilityInvocationClassifier {
    private AccessibilityInvocationClassifier() { }

    public static AccessibilityInvocationClass classify(String methodName) {
        String name = normalize(methodName);
        if (name.isEmpty()) return AccessibilityInvocationClass.UNKNOWN;
        if (contains(name, "setaccessibilitywindowattributes")) {
            return AccessibilityInvocationClass.APP_LOCAL_WINDOW_METADATA;
        }
        if (contains(name, "sendaccessibilityevent")) {
            return AccessibilityInvocationClass.APP_LOCAL_EVENT;
        }
        if (contains(name, "interrupt")) {
            return AccessibilityInvocationClass.HOST_ACCESSIBILITY_STATE_MUTATION;
        }
        if (contains(name, "securesetting") || contains(name, "putsecure")
                || contains(name, "enableservice") || contains(name, "setaccessibilityservice")) {
            return AccessibilityInvocationClass.SECURE_SETTING_MUTATION;
        }
        if (contains(name, "getwindows") || contains(name, "getwindow")
                || contains(name, "getrootinactivewindow") || contains(name, "findfocus")
                || contains(name, "findaccessibilitynode") || contains(name, "getaccessibilitynode")
                || contains(name, "performaccessibilityaction")) {
            return AccessibilityInvocationClass.CROSS_APP_ACCESSIBILITY_DATA;
        }
        if (starts(name, "set") || starts(name, "add") || starts(name, "remove")
                || starts(name, "clear") || starts(name, "enable") || starts(name, "disable")
                || starts(name, "register") || starts(name, "unregister")) {
            if (contains(name, "client") || contains(name, "connection")
                    || contains(name, "listener") || contains(name, "callback")) {
                return AccessibilityInvocationClass.HOST_ACCESSIBILITY_STATE_READ;
            }
            return AccessibilityInvocationClass.HOST_ACCESSIBILITY_STATE_MUTATION;
        }
        if (starts(name, "is") || starts(name, "get") || starts(name, "has")) {
            return AccessibilityInvocationClass.HOST_ACCESSIBILITY_STATE_READ;
        }
        return AccessibilityInvocationClass.UNKNOWN;
    }

    static boolean isCrossAppEvent(Object event, String guestPackage) {
        if (event == null || guestPackage == null || guestPackage.isEmpty()) return false;
        try {
            Object packageName = event.getClass().getMethod("getPackageName").invoke(event);
            if (packageName == null) return false;
            String value = String.valueOf(packageName);
            return !value.isEmpty() && !guestPackage.equals(value);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String value, String needle) {
        return value.contains(normalize(needle));
    }

    private static boolean starts(String value, String prefix) {
        return value.startsWith(normalize(prefix));
    }
}
