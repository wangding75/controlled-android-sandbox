package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared bounded value conversion and identity helpers for peripheral service projections. */
final class PeripheralInvocationValues {
    private PeripheralInvocationValues() { }

    static int adapterState(String value) {
        return switch (value) {
            case "TURNING_ON" -> 2;
            case "ON" -> 3;
            case "TURNING_OFF" -> 4;
            default -> 1;
        };
    }

    static boolean host(String mode) {
        return VirtualLocationProfileSnapshot.MODE_HOST.equals(mode);
    }

    static boolean blocked(String mode) {
        return VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(mode);
    }

    static boolean cleanup(String name) {
        return containsAny(name, "release", "destroy", "unregister", "remove", "cancel",
                "stop", "close");
    }

    static Set<Object> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    static void addBounded(Set<Object> target, Object value, int maximum, String message) {
        if (!target.contains(value) && target.size() >= maximum) {
            throw new IllegalStateException(message);
        }
        target.add(value);
    }

    static Object firstIdentity(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean || value.getClass().isEnum()) {
                continue;
            }
            return value;
        }
        return null;
    }

    static void removeIdentity(Set<Object> target, Object[] arguments) {
        Object value = firstIdentity(arguments);
        if (value != null) target.remove(value);
    }

    static String firstString(Object[] arguments) {
        if (arguments == null) return "";
        for (Object value : arguments) {
            if (value instanceof String text) return text;
        }
        return "";
    }

    static boolean matchesPrefix(String method, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (InvocationMethodMatcher.startsWith(method, prefix)) return true;
        }
        return false;
    }

    static Object emptyCollection(Class<?> type) {
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        if (Map.class.isAssignableFrom(type)) return Map.of();
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)
                || type == Object.class) {
            return List.of();
        }
        return null;
    }

    static Object stringArrayOrList(Class<?> type, List<String> values) {
        if (type == String[].class) return values.toArray(new String[0]);
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)
                || type == Object.class) {
            return List.copyOf(values);
        }
        if (type == String.class) return values.isEmpty() ? "" : values.get(0);
        return null;
    }

    static Object stringValue(Class<?> type, String value) {
        if (type == String.class || type == Object.class) return value;
        return null;
    }

    static Object booleanValue(Class<?> type, boolean value) {
        if (type == boolean.class || type == Boolean.class) return value;
        if (type == int.class || type == Integer.class) return value ? 1 : 0;
        if (type == long.class || type == Long.class) return value ? 1L : 0L;
        return value;
    }

    static Object numeric(Class<?> type, long value) {
        if (type == int.class || type == Integer.class) return (int) value;
        if (type == long.class || type == Long.class) return value;
        if (type == short.class || type == Short.class) return (short) value;
        if (type == byte.class || type == Byte.class) return (byte) value;
        if (type == boolean.class || type == Boolean.class) return value != 0L;
        return value;
    }

    static Object emptyValue(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == float.class || type == Float.class) return 0f;
        if (type == double.class || type == Double.class) return 0d;
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        if (Map.class.isAssignableFrom(type)) return Map.of();
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)
                || type == Object.class) {
            return List.of();
        }
        return null;
    }

    static Object successValue(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        return null;
    }

    static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(normalize(fragment))) return true;
        }
        return false;
    }

    static String normalize(String value) {
        return value == null ? "" : value.replace("_", "").replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }
}
