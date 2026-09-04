package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualBluetoothDeviceSnapshot;
import com.warden.controlledsandbox.contract.VirtualCellInfoSnapshot;
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
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Version-tolerant reflective construction for framework result objects. */
public final class FrameworkDeviceObjectFactory {
    private static volatile boolean WIFI_FACTORY_LOGGED;
    private FrameworkDeviceObjectFactory() { }

    static Object location(Class<?> type, VirtualLocationProfileSnapshot profile) {
        profile = profile.sampleAt(System.currentTimeMillis(), System.nanoTime());
        if (type != null && "android.location.LocationResult".equals(type.getName())) {
            Object location = locationValue(android.location.Location.class, profile);
            try {
                Method wrap = type.getDeclaredMethod("wrap", List.class);
                wrap.setAccessible(true);
                return wrap.invoke(null, List.of(location));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("VIRTUAL_LOCATION_RESULT_ADAPTER_FAILED", error);
            }
        }
        return locationValue(type, profile);
    }

    private static Object locationValue(Class<?> type, VirtualLocationProfileSnapshot profile) {
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
        try {
            Object wifiSsid = wifiSsid(profile.ssid());
            write(value, new String[]{"setSSID"}, new String[]{"mWifiSsid"}, wifiSsid);
            if (!WIFI_FACTORY_LOGGED) {
                WIFI_FACTORY_LOGGED = true;
                Object stored = null;
                Field field = findField(value.getClass(), "mWifiSsid");
                if (field != null) {
                    field.setAccessible(true);
                    stored = field.get(value);
                }
                android.util.Log.i("CS_WIFI_FACTORY", "ssid=" + profile.ssid()
                        + " object=" + wifiSsid + " stored=" + stored
                        + " field=" + (field == null ? "none" : field.getType().getName()));
            }
        } catch (Throwable error) {
            // Older API images store SSID as a String; the compatibility write below covers it.
            if (!WIFI_FACTORY_LOGGED) {
                WIFI_FACTORY_LOGGED = true;
                android.util.Log.i("CS_WIFI_FACTORY", "WifiSsid adapter unavailable error="
                        + error.getClass().getName());
            }
        }
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
        Object wifiSsid;
        try {
            wifiSsid = wifiSsid(network.ssid());
        } catch (ReflectiveOperationException error) {
            // ScanResult on pre-WifiSsid images stores the SSID as a String.
            wifiSsid = network.ssid();
        }
        write(value, new String[]{"setSsid", "setSSID"}, new String[]{"SSID", "mWifiSsid", "ssid"},
                wifiSsid);
        write(value, new String[]{"setSsid", "setSSID"}, new String[]{"SSID", "ssid"}, network.ssid());
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

    /**
     * WifiSsid is hidden and moved between the boot image and the Wi-Fi module across API
     * levels.  Android 12 exposes createFromByteArray/createFromAsciiEncoded; older images
     * used different names.  Keep the adaptation at the framework object boundary so the
     * virtual profile never falls back to the host's SSID.
     */
    private static Object wifiSsid(String ssid) throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.net.wifi.WifiSsid");
        byte[] bytes = ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String name : new String[]{"createFromByteArray", "fromBytes"}) {
            try {
                Method factory = type.getDeclaredMethod(name, byte[].class);
                factory.setAccessible(true);
                return factory.invoke(null, bytes);
            } catch (NoSuchMethodException missing) {
                // Continue with the next version-specific factory.
            }
        }
        try {
            Method factory = type.getDeclaredMethod("createFromAsciiEncoded", String.class);
            factory.setAccessible(true);
            return factory.invoke(null, ssid);
        } catch (NoSuchMethodException missing) {
            // Fall through to the public octet buffer used by the API-31/32 implementation.
        }
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object value = constructor.newInstance();
        Field octets = findField(type, "octets");
        if (octets == null) throw new NoSuchFieldException("WifiSsid.octets");
        octets.setAccessible(true);
        Object output = octets.get(value);
        if (output instanceof java.io.ByteArrayOutputStream stream) {
            stream.write(bytes, 0, bytes.length);
            return value;
        }
        throw new IllegalStateException("VIRTUAL_WIFI_SSID_OCTETS_UNSUPPORTED");
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

    static Object cellInfo(Class<?> type, VirtualCellInfoSnapshot cell) {
        // CellInfo itself is abstract on API 32+.  Returning an allocated base object makes
        // the List<CellInfo> call appear non-empty but fails as soon as a Guest asks for the
        // identity or signal strength.  Build the concrete RAT object and its identity/signal
        // children through the hidden, version-specific constructors instead.
        String technology = cell.technology();
        if (VirtualCellInfoSnapshot.LTE.equals(technology)) {
            try {
                Class<?> infoType = Class.forName("android.telephony.CellInfoLte");
                Class<?> identityType = Class.forName("android.telephony.CellIdentityLte");
                Class<?> signalType = Class.forName("android.telephony.CellSignalStrengthLte");
                Object identity = constructLteIdentity(identityType, cell);
                Object signal = constructLteSignal(signalType, cell);
                Object value = constructLteInfo(type, infoType, identity, signal, cell);
                if (value != null) return value;
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IllegalStateException("VIRTUAL_CELL_LTE_ADAPTER_FAILED", error);
            }
        }
        throw new IllegalStateException("VIRTUAL_CELL_TECHNOLOGY_ADAPTER_REQUIRED:" + technology);
    }

    private static Object constructLteIdentity(Class<?> type, VirtualCellInfoSnapshot cell)
            throws ReflectiveOperationException {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int[].class, int.class,
                    String.class, String.class, String.class, String.class,
                    java.util.Collection.class,
                    Class.forName("android.telephony.ClosedSubscriberGroupInfo"));
            constructor.setAccessible(true);
            return constructor.newInstance((int) cell.cid(), cell.pci(), cell.tac(), cell.arfcn(),
                    new int[0], -1, String.format(java.util.Locale.ROOT, "%03d", cell.mcc()),
                    String.format(java.util.Locale.ROOT, "%02d", cell.mnc()), null, null,
                    java.util.List.of(), null);
        } catch (NoSuchMethodException missingModernConstructor) {
            // Continue with API-specific legacy constructors below.
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(cell.mcc(), cell.mnc(), (int) cell.cid(), cell.pci(),
                    cell.tac(), cell.arfcn());
        } catch (NoSuchMethodException missingEarfcnConstructor) {
            // API 26-28 exposed only the five-argument identity constructor.
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(cell.mcc(), cell.mnc(), (int) cell.cid(), cell.pci(), cell.tac());
        } catch (NoSuchMethodException missingLegacyConstructor) {
            Constructor<?> constructor = type.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(cell.mcc(), cell.mnc(), (int) cell.cid(), cell.pci(),
                    cell.tac(), cell.arfcn());
        }
    }

    private static Object constructLteSignal(Class<?> type, VirtualCellInfoSnapshot cell)
            throws ReflectiveOperationException {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            int rsrp = cell.signalLevel();
            return constructor.newInstance(asuFromDbm(rsrp), rsrp, -10, 0, 0, 0);
        } catch (NoSuchMethodException missingParameterizedConstructor) {
            Object value = construct(type, null);
            write(value, new String[]{}, new String[]{"mSignalStrength"},
                    asuFromDbm(cell.signalLevel()));
            write(value, new String[]{}, new String[]{"mRsrp"}, cell.signalLevel());
            return value;
        }
    }

    private static Object constructLteInfo(Class<?> requestedType, Class<?> infoType,
            Object identity, Object signal, VirtualCellInfoSnapshot cell)
            throws ReflectiveOperationException {
        Class<?> target = requestedType == null || requestedType == Object.class
                || requestedType.isInterface() || java.lang.reflect.Modifier.isAbstract(requestedType.getModifiers())
                ? infoType : requestedType;
        for (Constructor<?> constructor : target.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 5 && parameters[0] == int.class && parameters[1] == boolean.class
                    && parameters[2] == long.class && parameters[3].isInstance(identity)
                    && parameters[4].isInstance(signal)) {
                constructor.setAccessible(true);
                Object value = constructor.newInstance(cell.registered() ? 1 : 0, cell.registered(),
                        System.nanoTime(), identity, signal);
                return value;
            }
        }
        Object value = construct(target, null);
        write(value, new String[]{"setRegistered"}, new String[]{"mRegistered"}, cell.registered());
        write(value, new String[]{"setCellConnectionStatus"}, new String[]{"mCellConnectionStatus"},
                cell.registered() ? 1 : 0);
        write(value, new String[]{"setTimeStamp"}, new String[]{"mTimeStamp"}, System.nanoTime());
        write(value, new String[]{"setCellIdentity"}, new String[]{"mCellIdentityLte"}, identity);
        write(value, new String[]{"setCellSignalStrength"}, new String[]{"mCellSignalStrengthLte"}, signal);
        return value;
    }

    private static int asuFromDbm(int dbm) {
        return dbm == Integer.MAX_VALUE ? 99 : Math.max(0, Math.min(31, dbm + 140));
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

    static Object list(Method method, List<?> profiles, ElementFactory factory) {
        List<Object> result = projectedList(method, profiles, factory);
        return adaptCollection(result, method.getReturnType());
    }

    static Set<Object> set(Method method, List<?> profiles, ElementFactory factory) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(projectedList(method, profiles, factory)));
    }

    private static List<Object> projectedList(Method method, List<?> profiles, ElementFactory factory) {
        Class<?> elementType = listElementType(method);
        if (elementType == null) elementType = inferredElementType(method);
        if (elementType == null) {
            // Host-side test interfaces often erase the element type; snapshots remain deterministic values.
            return Collections.unmodifiableList(new ArrayList<>(profiles));
        }
        List<Object> result = new ArrayList<>(profiles.size());
        for (Object profile : profiles) result.add(factory.create(elementType, profile));
        return Collections.unmodifiableList(result);
    }

    private static Object adaptCollection(List<Object> values, Class<?> returnType) {
        if (returnType == null || List.class.isAssignableFrom(returnType)
                || returnType == Object.class) return values;
        if (returnType.isArray()) {
            Object array = Array.newInstance(returnType.getComponentType(), values.size());
            for (int index = 0; index < values.size(); index++) {
                Array.set(array, index, values.get(index));
            }
            return array;
        }
        if (returnType.getName().endsWith("ParceledListSlice")) {
            try {
                HiddenApiAccess.ensureExemptions();
                Constructor<?> constructor = null;
                for (Constructor<?> candidate : returnType.getDeclaredConstructors()) {
                    Class<?>[] parameters = candidate.getParameterTypes();
                    if (parameters.length == 1 && parameters[0].isAssignableFrom(List.class)) {
                        constructor = candidate;
                        break;
                    }
                }
                if (constructor == null) {
                    throw new NoSuchMethodException(returnType.getName() + ".<init>(List)");
                }
                constructor.setAccessible(true);
                return constructor.newInstance(values);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("VIRTUAL_DEVICE_COLLECTION_SLICE_UNSUPPORTED:"
                        + returnType.getName(), error);
            }
        }
        throw new IllegalStateException("VIRTUAL_DEVICE_COLLECTION_RETURN_UNSUPPORTED:"
                + returnType.getName());
    }

    private static Class<?> inferredElementType(Method method) {
        if (method == null) return null;
        String name = method.getName().toLowerCase(java.util.Locale.ROOT);
        try {
            if (name.contains("scanresult")) {
                return Class.forName("android.net.wifi.ScanResult");
            }
            if (name.contains("subscriptioninfo")) {
                return Class.forName("android.telephony.SubscriptionInfo");
            }
            if (name.contains("cellinfo")) {
                return Class.forName("android.telephony.CellInfo");
            }
            if (name.contains("sensorlist")) {
                return Class.forName("android.hardware.Sensor");
            }
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("VIRTUAL_DEVICE_COLLECTION_ELEMENT_UNAVAILABLE:" + name,
                    error);
        }
        return null;
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
