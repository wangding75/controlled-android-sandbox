package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualInputMethodProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Reversible mutation of WindowManager.LayoutParams and EditorInfo-like framework objects. */
final class InteractionObjectRewriter {
    private static final int FLAG_SECURE = 0x00002000;
    private static final int FIRST_SYSTEM_WINDOW = 2000;

    private InteractionObjectRewriter() { }

    static RewriteScope rewrite(Object[] arguments, GuestIdentity identity,
            VirtualWindowPolicySnapshot window, VirtualInputMethodProfileSnapshot input) {
        if (arguments == null || arguments.length == 0) return RewriteScope.EMPTY;
        List<Restore> restores = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (Object argument : arguments) {
                visit(argument, identity, window, input, restores, visited, 0);
            }
            return new RewriteScope(restores);
        } catch (RuntimeException error) {
            restore(restores);
            throw error;
        }
    }

    private static void visit(Object value, GuestIdentity identity,
            VirtualWindowPolicySnapshot window, VirtualInputMethodProfileSnapshot input,
            List<Restore> restores, Set<Object> visited, int depth) {
        if (value == null || depth > 5) return;
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof String || value instanceof Number
                || value instanceof Boolean || type.isEnum()) return;
        if (!visited.add(value)) return;
        if (type.isArray() && !type.getComponentType().isPrimitive()) {
            int length = Math.min(java.lang.reflect.Array.getLength(value), 64);
            for (int index = 0; index < length; index++) {
                visit(java.lang.reflect.Array.get(value, index), identity, window, input,
                        restores, visited, depth + 1);
            }
            return;
        }
        String name = type.getName();
        if (name.contains("WindowManager$LayoutParams") || hasFields(type, "packageName", "flags", "type")) {
            rewriteLayoutParams(value, identity, window, restores);
            return;
        }
        if (name.endsWith("EditorInfo") || hasFields(type, "packageName", "fieldId")) {
            rewriteEditorInfo(value, identity, input, restores);
            return;
        }
        if (name.contains("Attribution") || name.contains("Input") || name.contains("Window")) {
            Class<?> cursor = type;
            while (cursor != null) {
                for (Field field : cursor.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    String fieldName = field.getName();
                    if (!(fieldName.contains("editor") || fieldName.contains("window")
                            || fieldName.contains("attribution") || fieldName.equals("mNext"))) continue;
                    try {
                        field.setAccessible(true);
                        visit(field.get(value), identity, window, input, restores, visited, depth + 1);
                    } catch (Throwable ignored) { }
                }
                cursor = cursor.getSuperclass();
            }
        }
    }

    private static void rewriteLayoutParams(Object value, GuestIdentity identity,
            VirtualWindowPolicySnapshot policy, List<Restore> restores) {
        Field packageName = findField(value.getClass(), "packageName", "mPackageName");
        if (policy.rewritePackageName() && packageName != null) {
            set(value, packageName, identity.hostPackageName(), restores);
        }
        Field typeField = findField(value.getClass(), "type", "mType");
        if (typeField != null) {
            try {
                typeField.setAccessible(true);
                Object raw = typeField.get(value);
                if (raw instanceof Integer && (Integer) raw >= FIRST_SYSTEM_WINDOW
                        && !policy.allowSystemAlertWindows()) {
                    throw new SecurityException("VIRTUAL_SYSTEM_ALERT_WINDOW_DENIED");
                }
            } catch (SecurityException error) {
                throw error;
            } catch (Throwable ignored) { }
        }
        Field flagsField = findField(value.getClass(), "flags", "mFlags");
        if (flagsField != null && !policy.allowSecureFlag()) {
            try {
                flagsField.setAccessible(true);
                Object raw = flagsField.get(value);
                if (raw instanceof Integer) set(value, flagsField, ((Integer) raw) & ~FLAG_SECURE, restores);
            } catch (RuntimeException error) { throw error; }
            catch (Throwable ignored) { }
        }
    }

    private static void rewriteEditorInfo(Object value, GuestIdentity identity,
            VirtualInputMethodProfileSnapshot policy, List<Restore> restores) {
        Field packageName = findField(value.getClass(), "packageName", "mPackageName");
        if (packageName != null) set(value, packageName, identity.hostPackageName(), restores);
        Field user = findField(value.getClass(), "targetInputMethodUserId", "mTargetInputMethodUserId");
        if (user != null) set(value, user, 0, restores);
        Field extras = findField(value.getClass(), "extras");
        if (extras != null && !policy.allowInlineSuggestions()) {
            // Do not inspect or clear arbitrary extras; the identity fields above are the bounded mutation surface.
        }
    }

    private static void set(Object owner, Field field, Object value, List<Restore> restores) {
        try {
            field.setAccessible(true);
            Object original = field.get(owner);
            field.set(owner, value);
            restores.add(new Restore(owner, field, original));
        } catch (Throwable error) {
            throw new IllegalStateException("Cannot rewrite framework interaction field " + field.getName(), error);
        }
    }

    private static boolean hasFields(Class<?> type, String... names) {
        for (String name : names) if (findField(type, name) == null) return false;
        return true;
    }
    private static Field findField(Class<?> type, String... names) {
        Class<?> cursor = type;
        while (cursor != null) {
            for (String name : names) {
                try { return cursor.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }
    private static void restore(List<Restore> restores) {
        for (int index = restores.size() - 1; index >= 0; index--) {
            Restore restore = restores.get(index);
            try { restore.field.setAccessible(true); restore.field.set(restore.owner, restore.value); }
            catch (Throwable ignored) { }
        }
    }
    private record Restore(Object owner, Field field, Object value) { }

    static final class RewriteScope implements AutoCloseable {
        static final RewriteScope EMPTY = new RewriteScope(List.of());
        private final List<Restore> restores;
        private boolean closed;
        RewriteScope(List<Restore> restores) { this.restores = restores; }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            restore(restores);
        }
    }
}
