package com.warden.controlledsandbox.framework.core;

import android.content.ComponentName;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualContextHubSnapshot;
import com.warden.controlledsandbox.contract.VirtualStorageStatsProfileSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reflection-safe value adapters for privileged framework service results. */
final class PrivilegedInvocationValues {
    private PrivilegedInvocationValues() { }

    static Object component(Class<?> returnType, String flattened) {
        if (flattened == null || flattened.isEmpty()) return null;
        int separator = flattened.indexOf('/');
        if (separator <= 0 || separator == flattened.length() - 1) return null;
        ComponentName value = new ComponentName(
                flattened.substring(0, separator), flattened.substring(separator + 1));
        if (returnType == Object.class || returnType.isInstance(value)) return value;
        if (returnType == String.class) return flattened;
        return null;
    }

    static Object storageStats(
            Class<?> returnType, VirtualStorageStatsProfileSnapshot profile, boolean external) {
        if (returnType == Object.class || Map.class.isAssignableFrom(returnType)) {
            LinkedHashMap<String, Long> values = new LinkedHashMap<>();
            if (external) {
                values.put("totalBytes", profile.appBytes() + profile.dataBytes()
                        + profile.cacheBytes() + profile.externalCacheBytes());
                values.put("appBytes", profile.appBytes());
                values.put("cacheBytes", profile.externalCacheBytes());
            } else {
                values.put("appBytes", profile.appBytes());
                values.put("dataBytes", profile.dataBytes());
                values.put("cacheBytes", profile.cacheBytes());
                values.put("externalCacheBytes", profile.externalCacheBytes());
            }
            return Map.copyOf(values);
        }
        Object value = instantiate(returnType);
        if (value == null) return null;
        if (external) {
            setLong(value, "totalBytes", profile.appBytes() + profile.dataBytes()
                    + profile.cacheBytes() + profile.externalCacheBytes());
            setLong(value, "appBytes", profile.appBytes());
            setLong(value, "cacheBytes", profile.externalCacheBytes());
        } else {
            setLong(value, "appBytes", profile.appBytes());
            setLong(value, "dataBytes", profile.dataBytes());
            setLong(value, "cacheBytes", profile.cacheBytes());
            setLong(value, "externalCacheBytes", profile.externalCacheBytes());
        }
        return value;
    }

    static Object contextHubs(Class<?> returnType, List<VirtualContextHubSnapshot> hubs) {
        if (returnType.isArray() && returnType.getComponentType() == int.class) {
            int[] ids = new int[hubs.size()];
            for (int index = 0; index < hubs.size(); index++) ids[index] = hubs.get(index).hubId();
            return ids;
        }
        if (returnType == Object.class) return List.copyOf(hubs);
        if (List.class.isAssignableFrom(returnType) || Iterable.class.isAssignableFrom(returnType)) {
            ArrayList<Object> out = new ArrayList<>();
            for (VirtualContextHubSnapshot hub : hubs) {
                Object value = reflectiveContextHub(hub);
                if (value == null) return List.of();
                out.add(value);
            }
            return List.copyOf(out);
        }
        return null;
    }

    static Object contextHub(Class<?> returnType, VirtualContextHubSnapshot hub) {
        if (hub == null) return null;
        if (returnType == Object.class) return hub;
        Object value = reflectiveContextHub(hub);
        return value != null && returnType.isInstance(value) ? value : null;
    }

    static Bundle systemUpdateBundle(String status, String title, String version,
            String securityPatch, int progressPercent, long receivedTimeMs) {
        Bundle out = new Bundle();
        out.putInt("status", statusCode(status));
        out.putString("status_name", status);
        out.putString("title", title);
        out.putString("version", version);
        out.putString("security_patch", securityPatch);
        out.putInt("progress", progressPercent);
        out.putLong("received_time_ms", receivedTimeMs);
        return out;
    }

    static Object graphicsStats(Class<?> returnType, long totalFrames,
            long jankyFrames, long lastResetTimeMs) {
        if (returnType == Bundle.class || returnType == Object.class) {
            Bundle out = new Bundle();
            out.putLong("total_frames", totalFrames);
            out.putLong("janky_frames", jankyFrames);
            out.putLong("last_reset_time_ms", lastResetTimeMs);
            return out;
        }
        if (Map.class.isAssignableFrom(returnType)) {
            return Map.of("totalFrames", totalFrames, "jankyFrames", jankyFrames,
                    "lastResetTimeMs", lastResetTimeMs);
        }
        return null;
    }

    private static Object reflectiveContextHub(VirtualContextHubSnapshot hub) {
        try {
            Class<?> type = Class.forName("android.hardware.location.ContextHubInfo");
            Object value = instantiate(type);
            if (value == null) return null;
            setInt(value, "id", hub.hubId());
            setInt(value, "hubId", hub.hubId());
            setString(value, "name", hub.name());
            setString(value, "vendor", hub.vendor());
            setInt(value, "maxPacketLenBytes", hub.maximumPacketLengthBytes());
            setInt(value, "maximumPacketLengthBytes", hub.maximumPacketLengthBytes());
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setLong(Object target, String fieldName, long value) {
        setField(target, fieldName, value);
    }

    private static void setInt(Object target, String fieldName, int value) {
        setField(target, fieldName, value);
    }

    private static void setString(Object target, String fieldName, String value) {
        setField(target, fieldName, value);
    }

    private static void setField(Object target, String requested, Object value) {
        Class<?> type = target.getClass();
        for (Field field : type.getDeclaredFields()) {
            String fieldName = field.getName();
            if (fieldName.length() > 1 && fieldName.charAt(0) == 'm'
                    && Character.isUpperCase(fieldName.charAt(1))) {
                fieldName = Character.toLowerCase(fieldName.charAt(1)) + fieldName.substring(2);
            }
            String normalized = fieldName.replace("_", "").toLowerCase(java.util.Locale.ROOT);
            String expected = requested.replace("_", "").toLowerCase(java.util.Locale.ROOT);
            if (!normalized.equals(expected)) continue;
            try {
                field.setAccessible(true);
                if (field.getType() == long.class && value instanceof Number number) {
                    field.setLong(target, number.longValue());
                } else if (field.getType() == int.class && value instanceof Number number) {
                    field.setInt(target, number.intValue());
                } else {
                    field.set(target, value);
                }
            } catch (Throwable ignored) { }
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equalsIgnoreCase("set" + requested) || method.getParameterCount() != 1) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(target, value);
            } catch (Throwable ignored) { }
            return;
        }
    }

    private static int statusCode(String status) {
        return switch (status) {
            case "IDLE" -> 1;
            case "WAITING" -> 2;
            case "IN_PROGRESS" -> 3;
            case "PAUSED" -> 4;
            case "ERROR" -> 5;
            case "UPDATED" -> 6;
            default -> 0;
        };
    }
}
