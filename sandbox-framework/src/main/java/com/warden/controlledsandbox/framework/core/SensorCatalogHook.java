package com.warden.controlledsandbox.framework.core;

import android.content.Context;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reversible high-level SensorManager catalog replacement. Event delivery remains proxy-controlled. */
public final class SensorCatalogHook implements AutoCloseable {
    private final Object manager;
    private final Field listField;
    private final Object originalList;
    private final Field handleField;
    private final Object originalHandles;

    private SensorCatalogHook(Object manager, Field listField, Object originalList,
            Field handleField, Object originalHandles) {
        this.manager = manager; this.listField = listField; this.originalList = originalList;
        this.handleField = handleField; this.originalHandles = originalHandles;
    }

    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        VirtualSensorProfileSnapshot profile = identity.virtualServices().deviceServiceProfile().sensors();
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return () -> { };
        Object manager = context.getSystemService("sensor");
        if (manager == null) throw new IllegalStateException("System service unavailable: sensor");
        Field listField = firstField(manager.getClass(), "mFullSensorsList", "mSensorList");
        listField.setAccessible(true);
        Object originalList = listField.get(manager);
        Class<?> sensorType = sensorType(originalList);
        List<Object> virtualSensors = new ArrayList<>();
        if (!VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())) {
            for (VirtualSensorSnapshot sensor : profile.sensors()) {
                virtualSensors.add(FrameworkDeviceObjectFactory.sensor(sensorType, sensor));
            }
        }
        Field handleField = optionalField(manager.getClass(), "mHandleToSensor", "mHandleToSensorMap");
        Object originalHandles = null;
        try {
            listField.set(manager, virtualSensors);
            if (handleField != null) {
                handleField.setAccessible(true);
                originalHandles = handleField.get(manager);
                handleField.set(manager,
                        handleContainer(handleField.getType(), profile.sensors(), virtualSensors));
            }
            return new SensorCatalogHook(manager, listField, originalList, handleField, originalHandles);
        } catch (Throwable error) {
            try { listField.set(manager, originalList); } catch (Throwable ignored) { }
            if (handleField != null) {
                try { handleField.set(manager, originalHandles); } catch (Throwable ignored) { }
            }
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("Cannot project SensorManager catalog", error);
        }
    }

    private static Class<?> sensorType(Object originalList) throws Exception {
        if (originalList instanceof List<?> values && !values.isEmpty() && values.get(0) != null) {
            return values.get(0).getClass();
        }
        return Class.forName("android.hardware.Sensor");
    }

    private static Object handleContainer(Class<?> type, List<VirtualSensorSnapshot> profiles,
            List<Object> sensors) throws Exception {
        if (Map.class.isAssignableFrom(type)) {
            Map<Integer, Object> result = new LinkedHashMap<>();
            for (int index = 0; index < sensors.size(); index++) {
                result.put(profiles.get(index).handle(), sensors.get(index));
            }
            return result;
        }
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object result = constructor.newInstance();
        Method put = type.getMethod("put", int.class, Object.class);
        for (int index = 0; index < sensors.size(); index++) {
            put.invoke(result, profiles.get(index).handle(), sensors.get(index));
        }
        return result;
    }

    private static Field firstField(Class<?> type, String... names) throws NoSuchFieldException {
        Field field = optionalField(type, names);
        if (field == null) throw new NoSuchFieldException(type.getName() + ".sensorCatalog");
        return field;
    }
    private static Field optionalField(Class<?> type, String... names) {
        for (String name : names) {
            try { return ReflectiveServiceHook.findField(type, name); }
            catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    @Override public void close() {
        try { listField.set(manager, originalList); } catch (Throwable ignored) { }
        if (handleField != null) try { handleField.set(manager, originalHandles); } catch (Throwable ignored) { }
    }
}
