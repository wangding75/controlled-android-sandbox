package com.warden.controlledsandbox.framework.core;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualUserProfileSnapshot;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared argument parsing and return-value helpers for application-environment services. */
final class ApplicationEnvironmentInvocationValues {
    private ApplicationEnvironmentInvocationValues() { }

    static Bundle restrictionsBundle(VirtualUserProfileSnapshot profile) {
        Bundle out = new Bundle();
        for (String key : profile.restrictions()) out.putBoolean(key, true);
        return out;
    }
    static Bundle applicationRestrictionsBundle(VirtualUserProfileSnapshot profile) {
        Bundle out = new Bundle();
        for (int index = 0; index < profile.applicationRestrictionKeys().size(); index++) {
            out.putString(profile.applicationRestrictionKeys().get(index),
                    profile.applicationRestrictionValues().get(index));
        }
        return out;
    }
    static Object callback(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                    || value.getClass().isEnum() || value.getClass().isArray() || value instanceof Iterable<?>) continue;
            return value;
        }
        return null;
    }
    static Object firstCompatible(Object[] arguments, Class<?> type) {
        if (arguments == null) return null;
        for (Object value : arguments) if (value != null && type.isInstance(value)) return value;
        return null;
    }
    static List<Object> flatten(Object[] arguments) {
        List<Object> out = new ArrayList<>();
        if (arguments == null) return out;
        for (Object value : arguments) {
            if (value instanceof Iterable<?> iterable) iterable.forEach(out::add);
            else if (value != null && value.getClass().isArray()) {
                for (int index = 0; index < Array.getLength(value); index++) out.add(Array.get(value, index));
            } else if (!appendListCarrier(out, value)) out.add(value);
        }
        return out;
    }
    private static boolean appendListCarrier(List<Object> out, Object value) {
        if (value == null) return false;
        try {
            Method getList = value.getClass().getMethod("getList");
            Object nested = getList.invoke(value);
            if (nested instanceof Iterable<?> iterable) {
                iterable.forEach(out::add);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return false;
    }
    static List<String> stringList(Object[] arguments) {
        List<String> out = new ArrayList<>();
        for (Object value : flatten(arguments)) if (value instanceof String text && !text.isBlank()) out.add(text);
        return List.copyOf(out);
    }
    static String disabledMessage(Object[] arguments) {
        List<String> values = stringList(arguments);
        return values.size() < 2 ? "" : values.get(values.size() - 1);
    }
    static String firstRelevantString(Object[] arguments) {
        if (arguments == null) return "";
        for (Object value : arguments) if (value instanceof String text && !text.isBlank()) return text;
        return "";
    }
    static String[] component(Object[] arguments) {
        if (arguments != null) for (Object value : arguments) {
            if (value == null) continue;
            try {
                Method pkg = value.getClass().getMethod("getPackageName");
                Method cls = value.getClass().getMethod("getClassName");
                return new String[]{String.valueOf(pkg.invoke(value)), String.valueOf(cls.invoke(value))};
            } catch (Throwable ignored) { }
            try {
                Method flatten = value.getClass().getMethod("flattenToString");
                String text = String.valueOf(flatten.invoke(value));
                int split = text.indexOf('/');
                if (split > 0) return new String[]{text.substring(0, split), text.substring(split + 1)};
            } catch (Throwable ignored) { }
        }
        return new String[]{"", ""};
    }
    static long[] timeRange(Object[] arguments) {
        List<Long> values = new ArrayList<>();
        if (arguments != null) for (Object value : arguments) if (value instanceof Long number && number >= 0L) values.add(number);
        long now = System.currentTimeMillis();
        if (values.size() >= 2) return new long[]{values.get(values.size() - 2), values.get(values.size() - 1)};
        return new long[]{Math.max(0L, now - 24L * 60L * 60L * 1000L), now};
    }
    static int firstInt(Object[] arguments, int fallback) {
        if (arguments != null) for (Object value : arguments) if (value instanceof Integer number) return number;
        return fallback;
    }
    static long firstLong(Object[] arguments, long fallback) {
        if (arguments != null) for (Object value : arguments) if (value instanceof Long number) return number;
        return fallback;
    }
    static void requireStatic(String mode, String domain, String operation) {
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(mode)) {
            throw new SecurityException("VIRTUAL_" + domain.toUpperCase(Locale.ROOT) + "_BLOCKED:" + operation);
        }
        if (!VirtualLocationProfileSnapshot.MODE_STATIC.equals(mode)) {
            throw new IllegalStateException("VIRTUAL_" + domain.toUpperCase(Locale.ROOT) + "_MODE_UNSUPPORTED:" + mode);
        }
    }
    static Object emptyValue(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type) || type == Object.class) return List.of();
        return null;
    }
    static Object nullValue(Class<?> type) { return type.isPrimitive() ? emptyValue(type) : null; }
    static Object falseValue(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        return null;
    }
    static Object successValue(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        return null;
    }
    static Object booleanResult(Class<?> type, boolean value) {
        if (FrameworkApplicationEnvironmentObjectFactory.isAndroidFuture(type)) return value;
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return value;
        if (type == int.class || type == Integer.class) return value ? 1 : 0;
        return value ? successValue(type) : falseValue(type);
    }
    static Object numeric(Class<?> type, long value) {
        if (type == int.class || type == Integer.class) return Math.toIntExact(value);
        if (type == long.class || type == Long.class) return value;
        return value;
    }
    static String normalize(String value) {
        return value == null ? "" : value.replace("_", "").toLowerCase(Locale.ROOT);
    }
    static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(normalize(needle))) return true;
        return false;
    }
    static boolean startsAny(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(normalize(prefix))) return true;
        return false;
    }
    static boolean isCleanup(String name) {
        return startsAny(name, "unregister", "remove", "close", "stop", "cancel", "release");
    }
}
