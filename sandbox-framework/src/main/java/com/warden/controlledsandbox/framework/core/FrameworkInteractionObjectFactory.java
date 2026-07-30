package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualDisplaySnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reflection-only creation of DisplayInfo/Point-like values without compile-time hidden APIs. */
final class FrameworkInteractionObjectFactory {
    private FrameworkInteractionObjectFactory() { }

    static Object displayInfo(Class<?> returnType, VirtualDisplaySnapshot profile) {
        if (returnType == void.class || returnType == Void.class) return null;
        if (returnType == Object.class) return profile;
        Object value = allocate(returnType);
        if (value == null) return null;
        put(value, profile.displayId(), "displayId", "mDisplayId");
        put(value, profile.name(), "name", "mName");
        put(value, "controlled-sandbox:" + profile.displayId(), "uniqueId", "mUniqueId");
        put(value, profile.widthPixels(), "logicalWidth", "appWidth", "smallestNominalAppWidth",
                "largestNominalAppWidth", "mWidth");
        put(value, profile.heightPixels(), "logicalHeight", "appHeight", "smallestNominalAppHeight",
                "largestNominalAppHeight", "mHeight");
        put(value, profile.densityDpi(), "logicalDensityDpi", "densityDpi", "mDensityDpi");
        put(value, profile.xdpi(), "physicalXDpi", "xdpi", "mXdpi");
        put(value, profile.ydpi(), "physicalYDpi", "ydpi", "mYdpi");
        put(value, profile.refreshRate(), "refreshRate", "mRefreshRate");
        put(value, profile.rotation(), "rotation", "mRotation");
        put(value, profile.state(), "state", "mState");
        put(value, profile.flags(), "flags", "mFlags");
        put(value, 1, "type", "mType");
        put(value, 1, "modeId", "defaultModeId");
        return value;
    }

    static Object point(Class<?> returnType, int x, int y) {
        if (returnType == void.class || returnType == Void.class) return null;
        if (returnType == Object.class) return new int[]{x, y};
        try {
            Constructor<?> constructor = returnType.getDeclaredConstructor(int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(x, y);
        } catch (Throwable ignored) { }
        Object value = allocate(returnType);
        if (value != null) populatePoint(value, x, y);
        return value;
    }

    static boolean populatePoint(Object value, int x, int y) {
        if (value == null) return false;
        boolean wrote = put(value, x, "x", "width", "mX");
        wrote |= put(value, y, "y", "height", "mY");
        return wrote;
    }

    static String taskDescription(Object value) {
        if (value == null) return "";
        for (String methodName : new String[]{"getLabel", "getDescription"}) {
            try {
                Method method = value.getClass().getMethod(methodName);
                method.setAccessible(true);
                Object result = method.invoke(value);
                if (result != null) return String.valueOf(result);
            } catch (Throwable ignored) { }
        }
        for (String fieldName : new String[]{"mLabel", "label", "mDescription", "description"}) {
            Field field = findField(value.getClass(), fieldName);
            if (field == null) continue;
            try { field.setAccessible(true); Object result = field.get(value); if (result != null) return String.valueOf(result); }
            catch (Throwable ignored) { }
        }
        return "";
    }

    static int intMember(Object value, int fallback, String... names) {
        if (value == null) return fallback;
        for (String name : names) {
            Field field = findField(value.getClass(), name);
            if (field == null) continue;
            try { field.setAccessible(true); Object result = field.get(value); if (result instanceof Number) return ((Number) result).intValue(); }
            catch (Throwable ignored) { }
        }
        return fallback;
    }

    private static Object allocate(Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) return null;
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Throwable ignored) { }
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field field = unsafeType.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
            return allocate.invoke(unsafe, type);
        } catch (Throwable ignored) { return null; }
    }

    private static boolean put(Object target, Object value, String... names) {
        boolean wrote = false;
        for (String name : names) {
            Field field = findField(target.getClass(), name);
            if (field == null || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Class<?> type = field.getType();
                if (value instanceof Number number) {
                    if (type == int.class || type == Integer.class) field.set(target, number.intValue());
                    else if (type == float.class || type == Float.class) field.set(target, number.floatValue());
                    else if (type == long.class || type == Long.class) field.set(target, number.longValue());
                    else if (type == double.class || type == Double.class) field.set(target, number.doubleValue());
                    else continue;
                } else if (value == null || type.isInstance(value) || type == String.class) {
                    field.set(target, value == null ? null : String.valueOf(value));
                } else continue;
                wrote = true;
            } catch (Throwable ignored) { }
        }
        return wrote;
    }
    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        return null;
    }
}
