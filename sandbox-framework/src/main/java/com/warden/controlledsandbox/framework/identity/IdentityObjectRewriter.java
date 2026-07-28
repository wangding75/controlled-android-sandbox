package com.warden.controlledsandbox.framework.identity;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Rewrites exact Guest identity values for host Binder calls and restores mutable arguments afterwards. */
public final class IdentityObjectRewriter {
    private IdentityObjectRewriter() { }

    public static RewriteScope rewriteArguments(Object[] arguments, GuestIdentity identity) {
        RewriteScope scope = new RewriteScope();
        if (arguments == null) return scope;
        for (int index = 0; index < arguments.length; index++) {
            Object value = arguments[index];
            Object rewritten = scalarToHost(value, identity);
            if (rewritten != value) {
                final int item = index;
                final Object original = value;
                arguments[index] = rewritten;
                scope.add(() -> arguments[item] = original);
                continue;
            }
            rewriteNested(value, identity, scope);
        }
        return scope;
    }

    public static Object rewriteResult(Object value, GuestIdentity identity) {
        if (value == null) return null;
        if (value instanceof String) {
            return identity.hostPackageName().equals(value) ? identity.packageName() : value;
        }
        if (value instanceof Integer) {
            return ((Integer) value) == identity.hostUid() ? identity.virtualUid() : value;
        }
        if (value instanceof String[]) {
            String[] source = (String[]) value;
            String[] copy = source.clone();
            for (int i = 0; i < copy.length; i++) {
                if (identity.hostPackageName().equals(copy[i])) copy[i] = identity.packageName();
            }
            return copy;
        }
        if (value instanceof ApplicationInfo) {
            ApplicationInfo info = new ApplicationInfo((ApplicationInfo) value);
            if (identity.hostPackageName().equals(info.packageName)) return identity.applicationInfo();
            return info;
        }
        if (value instanceof PackageInfo) {
            PackageInfo source = (PackageInfo) value;
            if (identity.hostPackageName().equals(source.packageName)) {
                PackageInfo info = new PackageInfo();
                info.packageName = identity.packageName();
                info.applicationInfo = identity.applicationInfo();
                return info;
            }
        }
        return value;
    }

    private static Object scalarToHost(Object value, GuestIdentity identity) {
        if (value instanceof String && identity.packageName().equals(value)) return identity.hostPackageName();
        if (value instanceof Integer && ((Integer) value) == identity.virtualUid()) return identity.hostUid();
        return value;
    }

    private static void rewriteNested(Object value, GuestIdentity identity, RewriteScope scope) {
        if (value == null) return;
        Class<?> type = value.getClass();
        if (type.isArray() && !type.getComponentType().isPrimitive()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object original = Array.get(value, i);
                Object rewritten = scalarToHost(original, identity);
                if (rewritten != original) {
                    final int index = i;
                    Array.set(value, i, rewritten);
                    scope.add(() -> Array.set(value, index, original));
                } else {
                    rewriteAttributionObject(original, identity, scope,
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
                }
            }
            return;
        }
        rewriteAttributionObject(value, identity, scope,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
    }

    private static void rewriteAttributionObject(Object value, GuestIdentity identity, RewriteScope scope,
                                                 java.util.Set<Object> visited, int depth) {
        if (value == null || depth > 8 || !visited.add(value)) return;
        String name = value.getClass().getName();
        if (!name.endsWith("AttributionSource") && !name.contains("Attribution")) return;
        Class<?> cursor = value.getClass();
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                String fieldName = field.getName();
                boolean packageField = (field.getType() == String.class)
                        && (fieldName.equals("mPackageName") || fieldName.equals("packageName"));
                boolean uidField = (field.getType() == int.class || field.getType() == Integer.class)
                        && (fieldName.equals("mUid") || fieldName.equals("uid"));
                boolean nextField = fieldName.equals("mNext") || fieldName.equals("next");
                boolean attributionField = nextField
                        || field.getType().getName().contains("Attribution");
                if (!packageField && !uidField && !attributionField) continue;
                try {
                    field.setAccessible(true);
                    Object original = field.get(value);
                    if (attributionField) {
                        rewriteAttributionObject(original, identity, scope, visited, depth + 1);
                        continue;
                    }
                    Object replacement = packageField && identity.packageName().equals(original)
                            ? identity.hostPackageName()
                            : uidField && original instanceof Integer && ((Integer) original) == identity.virtualUid()
                            ? identity.hostUid() : original;
                    if (replacement != original && !replacement.equals(original)) {
                        field.set(value, replacement);
                        scope.add(() -> {
                            try { field.set(value, original); } catch (IllegalAccessException ignored) { }
                        });
                    }
                } catch (Throwable ignored) { }
            }
            cursor = cursor.getSuperclass();
        }
    }

    public static final class RewriteScope implements AutoCloseable {
        private final List<Runnable> restorers = new ArrayList<>();
        void add(Runnable restorer) { restorers.add(restorer); }
        @Override public void close() {
            for (int i = restorers.size() - 1; i >= 0; i--) restorers.get(i).run();
            restorers.clear();
        }
    }
}
