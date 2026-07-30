package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualBluetoothDeviceSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonySlotSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Version-tolerant reflective construction for framework result objects. */
public final class FrameworkDeviceObjectFactory {
    private FrameworkDeviceObjectFactory() { }

    static Object location(Class<?> type, VirtualLocationProfileSnapshot profile) {
        Object value = construct(type, profile.provider());
        write(value, new String[]{"setProvider"}, new String[]{"mProvider"}, profile.provider());
        write(value, new String[]{"setLatitude"}, new String[]{"mLatitude"}, profile.latitude());
        write(value, new String[]{"setLongitude"}, new String[]{"mLongitude"}, profile.longitude());
        write(value, new String[]{"setAltitude"}, new String[]{"mAltitude"}, profile.altitudeMeters());
        write(value, new String[]{"setAccuracy"}, new String[]{"mHorizontalAccuracyMeters", "mAccuracy"},
                profile.accuracyMeters());
        write(value, new String[]{"setSpeed"}, new String[]{"mSpeedMetersPerSecond", "mSpeed"},
                profile.speedMetersPerSecond());
        write(value, new String[]{"setBearing"}, new String[]{"mBearingDegrees", "mBearing"},
                profile.bearingDegrees());
        long time = profile.timeMs() == 0L ? System.currentTimeMillis() : profile.timeMs();
        write(value, new String[]{"setTime"}, new String[]{"mTimeMs", "mTime"}, time);
        long elapsed = profile.elapsedRealtimeNanos() == 0L
                ? System.nanoTime() : profile.elapsedRealtimeNanos();
        write(value, new String[]{"setElapsedRealtimeNanos"},
                new String[]{"mElapsedRealtimeNanos"}, elapsed);
        write(value, new String[]{"setMock"}, new String[]{"mIsFromMockProvider"}, true);
        return value;
    }

    static Object wifiInfo(Class<?> type, VirtualWifiProfileSnapshot profile) {
        Object value = construct(type, null);
        write(value, new String[]{"setSSID"}, new String[]{"mSSID", "ssid"}, profile.ssid());
        write(value, new String[]{"setBSSID"}, new String[]{"mBSSID", "bssid"}, profile.bssid());
        write(value, new String[]{"setMacAddress"}, new String[]{"mMacAddress", "macAddress"},
                profile.macAddress());
        write(value, new String[]{"setNetworkId"}, new String[]{"mNetworkId", "networkId"},
                profile.networkId());
        write(value, new String[]{"setRssi"}, new String[]{"mRssi", "rssi"}, profile.rssi());
        write(value, new String[]{"setLinkSpeed"}, new String[]{"mLinkSpeed", "linkSpeed"},
                profile.linkSpeedMbps());
        write(value, new String[]{"setFrequency"}, new String[]{"mFrequency", "frequency"},
                profile.frequencyMhz());
        write(value, new String[]{"setInetAddress"}, new String[]{"mIpAddress", "ipAddress"},
                profile.ipv4Address());
        write(value, new String[]{"setHiddenSSID"}, new String[]{"mHiddenSSID", "hiddenSSID"},
                profile.hiddenSsid());
        write(value, new String[]{"setMeteredHint"}, new String[]{"mMeteredHint", "meteredHint"},
                profile.metered());
        return value;
    }

    static Object wifiNetwork(Class<?> type, VirtualWifiNetworkSnapshot network) {
        Object value = construct(type, null);
        write(value, new String[]{"setSsid", "setSSID"}, new String[]{"SSID", "mWifiSsid", "ssid"},
                network.ssid());
        write(value, new String[]{"setBssid", "setBSSID"}, new String[]{"BSSID", "bssid"},
                network.bssid());
        write(value, new String[]{"setCapabilities"}, new String[]{"capabilities", "mCapabilities"},
                network.capabilities());
        write(value, new String[]{"setFrequency"}, new String[]{"frequency", "mFrequency"},
                network.frequencyMhz());
        write(value, new String[]{"setLevel", "setRssi"}, new String[]{"level", "mLevel", "rssi"},
                network.rssi());
        return value;
    }


    static Object subscriptionInfo(Class<?> type, VirtualTelephonySlotSnapshot slot) {
        Object value = construct(type, null);
        write(value, new String[]{"setSubscriptionId"},
                new String[]{"mId", "mSubscriptionId", "subscriptionId"}, slot.subscriptionId());
        write(value, new String[]{"setSimSlotIndex"},
                new String[]{"mSimSlotIndex", "simSlotIndex"}, slot.slotIndex());
        write(value, new String[]{"setIccId"}, new String[]{"mIccId", "iccId"},
                slot.simSerialNumber());
        write(value, new String[]{"setNumber"}, new String[]{"mNumber", "number"},
                slot.line1Number());
        write(value, new String[]{"setDisplayName"},
                new String[]{"mDisplayName", "displayName"}, slot.carrierName());
        write(value, new String[]{"setCarrierName"},
                new String[]{"mCarrierName", "carrierName"}, slot.carrierName());
        write(value, new String[]{"setCountryIso"},
                new String[]{"mCountryIso", "countryIso"}, slot.simCountryIso());
        write(value, new String[]{"setDataRoaming"},
                new String[]{"mDataRoaming", "dataRoaming"}, slot.roaming() ? 1 : 0);
        write(value, new String[]{"setCardId"}, new String[]{"mCardId", "cardId"},
                slot.simSerialNumber());
        return value;
    }

    static Object bluetoothDevice(Class<?> type, VirtualBluetoothDeviceSnapshot device) {
        Object value = construct(type, device.address());
        write(value, new String[]{"setAddress"}, new String[]{"mAddress", "address"}, device.address());
        write(value, new String[]{"setName"}, new String[]{"mName", "name"}, device.name());
        write(value, new String[]{"setType"}, new String[]{"mType", "type"}, device.type());
        write(value, new String[]{"setBondState"}, new String[]{"mBondState", "bondState"},
                device.bondState());
        return value;
    }

    public static Object sensor(Class<?> type, VirtualSensorSnapshot sensor) {
        Object value = construct(type, null);
        write(value, new String[]{"setName"}, new String[]{"mName", "name"}, sensor.name());
        write(value, new String[]{"setVendor"}, new String[]{"mVendor", "vendor"}, sensor.vendor());
        write(value, new String[]{"setVersion"}, new String[]{"mVersion", "version"}, sensor.version());
        write(value, new String[]{"setHandle"}, new String[]{"mHandle", "handle"}, sensor.handle());
        write(value, new String[]{"setType"}, new String[]{"mType", "type"}, sensor.type());
        write(value, new String[]{"setMaxRange"}, new String[]{"mMaxRange", "maximumRange"},
                sensor.maximumRange());
        write(value, new String[]{"setResolution"}, new String[]{"mResolution", "resolution"},
                sensor.resolution());
        write(value, new String[]{"setPower"}, new String[]{"mPower", "power"}, sensor.powerMilliamp());
        write(value, new String[]{"setMinDelay"}, new String[]{"mMinDelay", "minimumDelay"},
                sensor.minimumDelayUs());
        write(value, new String[]{"setMaxDelay"}, new String[]{"mMaxDelay", "maximumDelay"},
                sensor.maximumDelayUs());
        return value;
    }

    static List<Object> list(Method method, List<?> profiles, ElementFactory factory) {
        Class<?> elementType = listElementType(method);
        if (elementType == null) {
            // Host-side test interfaces often erase the element type; snapshots remain deterministic values.
            return Collections.unmodifiableList(new ArrayList<>(profiles));
        }
        List<Object> result = new ArrayList<>(profiles.size());
        for (Object profile : profiles) result.add(factory.create(elementType, profile));
        return Collections.unmodifiableList(result);
    }

    static Set<Object> set(Method method, List<?> profiles, ElementFactory factory) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(list(method, profiles, factory)));
    }

    static Object dhcpInfo(Class<?> type, VirtualWifiProfileSnapshot profile) {
        Object value = construct(type, null);
        write(value, new String[0], new String[]{"ipAddress"}, profile.ipv4Address());
        write(value, new String[0], new String[]{"gateway"}, profile.ipv4Address() & 0x00ffffff | 0x01000000);
        write(value, new String[0], new String[]{"netmask"}, 0x00ffffff);
        write(value, new String[0], new String[]{"dns1"}, 0x08080808);
        write(value, new String[0], new String[]{"dns2"}, 0x04040808);
        write(value, new String[0], new String[]{"leaseDuration"}, 3600);
        return value;
    }

    private static Object construct(Class<?> type, String textArgument) {
        if (type == null || type == Object.class) return new Object();
        if (type == String.class) return textArgument == null ? "" : textArgument;
        try {
            if (textArgument != null) {
                try {
                    Constructor<?> constructor = type.getDeclaredConstructor(String.class);
                    constructor.setAccessible(true);
                    return constructor.newInstance(textArgument);
                } catch (NoSuchMethodException ignored) { }
            }
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Throwable error) {
            Object allocated = allocateWithoutConstructor(type);
            if (allocated != null) return allocated;
            throw new IllegalStateException("VIRTUAL_FRAMEWORK_OBJECT_CONSTRUCTION_UNSUPPORTED:"
                    + type.getName(), error);
        }
    }


    private static Object allocateWithoutConstructor(Class<?> type) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field field = unsafeType.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
            return allocate.invoke(unsafe, type);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void write(Object target, String[] setters, String[] fields, Object value) {
        if (target == null) return;
        for (String setter : setters) {
            Method method = compatibleMethod(target.getClass(), setter, value);
            if (method == null) continue;
            try { method.setAccessible(true); method.invoke(target, convert(value, method.getParameterTypes()[0])); return; }
            catch (Throwable ignored) { }
        }
        for (String fieldName : fields) {
            Field field = findField(target.getClass(), fieldName);
            if (field == null) continue;
            try { field.setAccessible(true); field.set(target, convert(value, field.getType())); return; }
            catch (Throwable ignored) { }
        }
    }

    private static Method compatibleMethod(Class<?> type, String name, Object value) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && compatible(method.getParameterTypes()[0], value)) return method;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && compatible(method.getParameterTypes()[0], value)) return method;
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        return null;
    }

    private static boolean compatible(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (type.isInstance(value)) return true;
        return type.isPrimitive() && value instanceof Number
                || (type == boolean.class && value instanceof Boolean);
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null || type.isInstance(value)) return value;
        if (value instanceof Number number) {
            if (type == int.class || type == Integer.class) return number.intValue();
            if (type == long.class || type == Long.class) return number.longValue();
            if (type == float.class || type == Float.class) return number.floatValue();
            if (type == double.class || type == Double.class) return number.doubleValue();
            if (type == short.class || type == Short.class) return number.shortValue();
            if (type == byte.class || type == Byte.class) return number.byteValue();
        }
        return value;
    }

    private static Class<?> listElementType(Method method) {
        Type type = method.getGenericReturnType();
        if (!(type instanceof ParameterizedType parameterized)) return null;
        Type[] arguments = parameterized.getActualTypeArguments();
        return arguments.length == 1 && arguments[0] instanceof Class<?> value ? value : null;
    }

    @FunctionalInterface interface ElementFactory { Object create(Class<?> type, Object profile); }
}
