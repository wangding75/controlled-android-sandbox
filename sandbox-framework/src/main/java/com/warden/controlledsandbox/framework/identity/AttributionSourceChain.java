package com.warden.controlledsandbox.framework.identity;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Reflection-only AttributionSource chain boundary shared by framework service adapters.
 *
 * <p>AttributionSource changed shape between API levels (a source can contain a state object,
 * an array/list of next states, or a linked next source).  Keeping this code independent from a
 * particular platform SDK lets the same Binder substrate preserve the Guest package/uid across
 * API 32, 35 and 36.  Attribution tags are opaque application data and are deliberately never
 * rewritten.</p>
 */
public final class AttributionSourceChain {
    private AttributionSourceChain() { }

    /** Rewrites Guest identity to the Host transport identity and records restoration actions. */
    public static void rewriteOutbound(Object[] arguments, GuestIdentity identity,
                                       IdentityObjectRewriter.RewriteScope scope) {
        if (arguments == null || identity == null || scope == null) return;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object argument : arguments) {
            rewrite(argument, identity.packageName(), identity.virtualUid(),
                    identity.hostPackageName(), identity.hostUid(), scope, visited, 0);
        }
    }

    /** Projects Host identity in a returned AttributionSource chain back to the Guest. */
    public static Object rewriteInbound(Object value, GuestIdentity identity) {
        if (value == null || identity == null) return value;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        rewrite(value, identity.hostPackageName(), identity.hostUid(),
                identity.packageName(), identity.virtualUid(), null, visited, 0);
        return value;
    }

    /** Returns true when an argument graph contains the requested package/uid identity. */
    public static boolean contains(Object[] arguments, String packageName, int uid) {
        if (arguments == null) return false;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object argument : arguments) {
            // A top-level package/UID is an AppOps identity argument.  Scalar values nested in
            // an arbitrary request object are not identity merely because they happen to equal
            // the Host UID (an operation code or user id can otherwise false-positive here).
            if (matchesScalar(argument, packageName, uid)
                    || contains(argument, packageName, uid, visited, 0)) return true;
        }
        return false;
    }

    /** Returns true when an argument graph contains the requested package identity. */
    public static boolean containsPackage(Object[] arguments, String packageName) {
        if (arguments == null || packageName == null) return false;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object argument : arguments) {
            if (packageName.equals(argument)
                    || containsPackage(argument, packageName, visited, 0)) return true;
        }
        return false;
    }

    private static boolean matchesScalar(Object value, String packageName, int uid) {
        return (packageName != null && packageName.equals(value))
                || (value instanceof Integer number && number == uid);
    }

    private static boolean contains(Object value, String packageName, int uid,
                                    Set<Object> visited, int depth) {
        if (value == null || depth > 10) return false;
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value.getClass().isEnum()) return false;
        if (!visited.add(value)) return false;
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 128);
            for (int index = 0; index < length; index++) {
                if (contains(Array.get(value, index), packageName, uid, visited, depth + 1)) return true;
            }
            return false;
        }
        if (!(type.getName().contains("Attribution") || type.getName().contains("AttributionSource"))) {
            return false;
        }
        for (Field field : fields(type)) {
            try {
                field.setAccessible(true);
                Object nested = field.get(value);
                if (isPackageField(field) && packageName != null
                        && packageName.equals(nested)) return true;
                if (isUidField(field) && nested instanceof Integer number && number == uid) {
                    return true;
                }
                if (nextField(field)
                        && contains(nested, packageName, uid, visited, depth + 1)) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static boolean containsPackage(Object value, String packageName,
                                            Set<Object> visited, int depth) {
        if (value == null || depth > 10) return false;
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value.getClass().isEnum()) return false;
        if (!visited.add(value)) return false;
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 128);
            for (int index = 0; index < length; index++) {
                if (containsPackage(Array.get(value, index), packageName, visited, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (!(type.getName().contains("Attribution")
                || type.getName().contains("AttributionSource"))) return false;
        for (Field field : fields(type)) {
            try {
                field.setAccessible(true);
                Object nested = field.get(value);
                if (isPackageField(field) && packageName.equals(nested)) return true;
                if (nextField(field)
                        && containsPackage(nested, packageName, visited, depth + 1)) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static void rewrite(Object value, String fromPackage, int fromUid,
                                String toPackage, int toUid,
                                IdentityObjectRewriter.RewriteScope scope,
                                Set<Object> visited, int depth) {
        if (value == null || depth > 10 || !visited.add(value)) return;
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                rewrite(item, fromPackage, fromUid, toPackage, toUid, scope, visited, depth + 1);
            }
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 128);
            for (int index = 0; index < length; index++) {
                rewrite(Array.get(value, index), fromPackage, fromUid, toPackage, toUid,
                        scope, visited, depth + 1);
            }
            return;
        }
        if (!(type.getName().contains("Attribution")
                || type.getName().contains("AttributionSource"))) return;
        for (Field field : fields(type)) {
            if (!identityField(field) && !nextField(field)) continue;
            try {
                field.setAccessible(true);
                Object original = field.get(value);
                if (isPackageField(field) && fromPackage != null && fromPackage.equals(original)) {
                    field.set(value, toPackage);
                    if (scope != null) scope.add(() -> restore(field, value, original));
                } else if (isUidField(field) && original instanceof Integer number
                        && number == fromUid) {
                    field.set(value, toUid);
                    if (scope != null) scope.add(() -> restore(field, value, original));
                } else if (nextField(field)) {
                    rewrite(original, fromPackage, fromUid, toPackage, toUid,
                            scope, visited, depth + 1);
                }
            } catch (Throwable ignored) { }
        }
    }

    private static boolean identityField(Field field) {
        return isPackageField(field) || isUidField(field);
    }

    private static boolean isPackageField(Field field) {
        return field.getType() == String.class
                && (field.getName().equals("packageName")
                || field.getName().equals("mPackageName"));
    }

    private static boolean isUidField(Field field) {
        return (field.getType() == int.class || field.getType() == Integer.class)
                && (field.getName().equals("uid") || field.getName().equals("mUid"));
    }

    private static boolean nextField(Field field) {
        return field.getName().equals("next") || field.getName().equals("mNext")
                || field.getType().getName().contains("Attribution");
    }

    private static Field[] fields(Class<?> type) {
        java.util.ArrayList<Field> result = new java.util.ArrayList<>();
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            Collections.addAll(result, cursor.getDeclaredFields());
            cursor = cursor.getSuperclass();
        }
        return result.toArray(new Field[0]);
    }

    private static void restore(Field field, Object target, Object value) {
        try { field.setAccessible(true); field.set(target, value); }
        catch (Throwable ignored) { }
    }
}
