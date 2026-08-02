package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualBluetoothDeviceSnapshot;
import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonySlotSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Deterministic result projection for device-facing framework services. */
final class DeviceServiceInvocationInterceptor {
    private final GuestIdentity identity;
    private final String service;

    DeviceServiceInvocationInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
    }

    Decision before(Method method, Object[] arguments) {
        VirtualDeviceServiceProfileSnapshot profile;
        try {
            profile = identity.virtualServices().deviceServiceProfile();
        } catch (IllegalStateException unavailable) {
            if ("VIRTUAL_DEVICE_PROFILE_AUTHORITY_REQUIRED".equals(unavailable.getMessage())
                    || "VIRTUAL_DEVICE_PROFILE_NOT_AVAILABLE".equals(unavailable.getMessage())) {
                return Decision.passThrough();
            }
            throw unavailable;
        }
        return switch (service) {
            case "location" -> location(method, arguments, profile.location());
            case "telephony", "phonesubinfo", "telephonyregistry", "subscription" ->
                    telephony(method, arguments, profile.telephony());
            case "wifi", "wifiscanner" -> wifi(method, profile.wifi());
            case "bluetooth" -> bluetooth(method, arguments, profile.bluetooth());
            case "sensor" -> sensors(method, arguments, profile.sensors());
            default -> Decision.passThrough();
        };
    }

    private Decision location(Method method, Object[] arguments,
            VirtualLocationProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (!isLocationOperation(name)) return Decision.passThrough();
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        if (isCleanup(name)) return Decision.handled(defaultValue(method.getReturnType()));
        requireMode(profile.mode(), "location", name);
        if (containsAny(name, "geofence", "testprovider", "injectlocation")) {
            throw new UnsupportedOperationException("VIRTUAL_LOCATION_OPERATION_UNSUPPORTED:" + method.getName());
        }
        if (containsAny(name, "isproviderenabled", "islocationenabled", "isautomotivelocationsupported")) {
            return Decision.handled(profile.providerEnabled());
        }
        if (containsAny(name, "getallproviders", "getproviders")) {
            return Decision.handled(profile.providerEnabled() ? List.of(profile.provider()) : List.of());
        }
        if (containsAny(name, "getbestprovider")) {
            return Decision.handled(profile.providerEnabled() ? profile.provider() : null);
        }
        if (containsAny(name, "getlastlocation", "getlastknownlocation", "getcurrentlocation")) {
            Object listener = callback(arguments);
            if (method.getReturnType() == void.class && listener != null) {
                Object location = FrameworkDeviceObjectFactory.location(locationCallbackType(listener), profile);
                invokeCallback(listener, new String[]{"onLocationChanged", "onLocationResult", "accept"}, location);
                return Decision.handled(null);
            }
            return Decision.handled(FrameworkDeviceObjectFactory.location(method.getReturnType(), profile));
        }
        if (containsAny(name, "requestlocationupdates", "registerlocationlistener")) {
            Object listener = callback(arguments);
            if (listener == null) {
                throw new UnsupportedOperationException("VIRTUAL_LOCATION_PENDING_INTENT_UNSUPPORTED");
            }
            Object location = FrameworkDeviceObjectFactory.location(locationCallbackType(listener), profile);
            invokeCallback(listener, new String[]{"onLocationChanged", "onLocationResult"}, location);
            identity.capabilityLeases().register("location", listener, () -> { });
            return Decision.handled(defaultValue(method.getReturnType()));
        }
        if (containsAny(name, "registergnss", "addgnss", "registernmea", "addnmea")) {
            Object listener = callback(arguments);
            if (listener == null) throw new UnsupportedOperationException("VIRTUAL_LOCATION_CALLBACK_REQUIRED");
            if (name.contains("nmea") && !profile.nmeaSentence().isEmpty()) {
                invokeCallback(listener, new String[]{"onNmeaMessage", "onNmeaReceived"},
                        profile.nmeaSentence(), System.currentTimeMillis());
            } else {
                invokeCallback(listener, new String[]{"onStarted"});
                invokeCallback(listener, new String[]{"onFirstFix"}, 0);
            }
            identity.capabilityLeases().register("location", listener, () -> { });
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "getgnss", "getgpsstatus")) {
            return Decision.handled(defaultValue(method.getReturnType()));
        }
        return failUnsupported("location", method);
    }

    private Decision telephony(Method method, Object[] arguments,
            VirtualTelephonyProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (!isTelephonyOperation(name)) return Decision.passThrough();
        requireMode(profile.mode(), "telephony", name);
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        VirtualTelephonySlotSnapshot slot = telephonySlot(profile, arguments);
        if (containsAny(name, "getactivesubscriptioninfolist", "getallsubinfolist",
                "getavailablesubscriptioninfolist", "getaccessiblesubscriptioninfolist")) {
            return Decision.handled(FrameworkDeviceObjectFactory.list(method, profile.slots(),
                    (type, value) -> FrameworkDeviceObjectFactory.subscriptionInfo(
                            type, (VirtualTelephonySlotSnapshot) value)));
        }
        if (containsAny(name, "getactivesubscriptioninfoforsimslotindex", "getsubscriptioninfoforslot")) {
            VirtualTelephonySlotSnapshot value = profile.slotForIndex(firstInt(arguments, -1));
            return Decision.handled(value == null ? null
                    : FrameworkDeviceObjectFactory.subscriptionInfo(method.getReturnType(), value));
        }
        if (containsAny(name, "getactivesubscriptioninfo", "getsubscriptioninfo")) {
            VirtualTelephonySlotSnapshot value = profile.slotForSubscription(firstInt(arguments, -1));
            return Decision.handled(value == null ? null
                    : FrameworkDeviceObjectFactory.subscriptionInfo(method.getReturnType(), value));
        }
        if (containsAny(name, "getactivesubidlist", "getsubidlist")) {
            int[] result = new int[profile.slots().size()];
            for (int index = 0; index < result.length; index++) {
                result[index] = profile.slots().get(index).subscriptionId();
            }
            return Decision.handled(result);
        }
        if (containsAny(name, "getactivesubinfocount", "getactivesubscriptioninfocount")) {
            return Decision.handled(profile.slots().size());
        }
        if (containsAny(name, "getactivesubinfocountmax", "getactivesubscriptioninfocountmax")) {
            return Decision.handled(Math.max(1, profile.slots().size()));
        }
        if (name.contains("isactivesubid")) {
            return Decision.handled(profile.slotForSubscription(firstInt(arguments, -1)) != null);
        }
        if (containsAny(name, "getslotindex", "getslotid", "getphoneid")) {
            VirtualTelephonySlotSnapshot value = profile.slotForSubscription(firstInt(arguments, -1));
            return Decision.handled(value == null ? -1 : value.slotIndex());
        }
        if (name.contains("getsubid")) {
            VirtualTelephonySlotSnapshot value = profile.slotForIndex(firstInt(arguments, -1));
            if (method.getReturnType().isArray()) {
                return Decision.handled(value == null ? new int[0] : new int[]{value.subscriptionId()});
            }
            return Decision.handled(value == null ? -1 : value.subscriptionId());
        }
        if (containsAny(name, "getdefaultdatasubid", "getdefaultdatasubscriptionid")) {
            return Decision.handled(profile.activeDataSubscriptionId());
        }
        if (containsAny(name, "getdefaultsmssubid", "getdefaultvoicesubid", "getdefaultsubid")) {
            return Decision.handled(profile.defaultSubscriptionId());
        }
        if (containsAny(name, "getdeviceid", "getimei")) return Decision.handled(slot == null ? null : slot.imei());
        if (name.contains("getmeid")) return Decision.handled(slot == null ? null : slot.meid());
        if (name.contains("getsubscriberid")) return Decision.handled(slot == null ? null : slot.subscriberId());
        if (containsAny(name, "getsimserialnumber", "geticcid")) {
            return Decision.handled(slot == null ? null : slot.simSerialNumber());
        }
        if (containsAny(name, "getline1number", "getmsisdn")) {
            return Decision.handled(slot == null ? null : emptyToNull(slot.line1Number()));
        }
        if (name.contains("getsimoperatorname") || name.contains("getnetworkoperatorname")) {
            return Decision.handled(slot == null ? "" : slot.carrierName());
        }
        if (name.contains("getsimoperator")) return Decision.handled(slot == null ? "" : slot.simOperator());
        if (name.contains("getnetworkoperator")) return Decision.handled(slot == null ? "" : slot.networkOperator());
        if (name.contains("getsimcountryiso")) return Decision.handled(slot == null ? "" : slot.simCountryIso());
        if (name.contains("getnetworkcountryiso")) return Decision.handled(slot == null ? "" : slot.networkCountryIso());
        if (containsAny(name, "getphonecount", "getactivemodemcount")) return Decision.handled(profile.slots().size());
        if (name.contains("getdefaultsubscription")) return Decision.handled(profile.defaultSubscriptionId());
        if (name.contains("getactivedatasubscription")) return Decision.handled(profile.activeDataSubscriptionId());
        if (name.contains("getphonetype")) return Decision.handled(slot == null ? 0 : slot.phoneType());
        if (name.contains("getsimstate")) return Decision.handled(slot == null ? 0 : slot.simState());
        if (containsAny(name, "getdatanetworktype", "getnetworktype")) {
            return Decision.handled(slot == null ? 0 : slot.dataNetworkType());
        }
        if (name.contains("getvoicenetworktype")) return Decision.handled(slot == null ? 0 : slot.voiceNetworkType());
        if (containsAny(name, "isdataenabled", "isuserdataenabled")) {
            return Decision.handled(slot != null && slot.dataEnabled());
        }
        if (name.contains("isnetworkroaming")) return Decision.handled(slot != null && slot.roaming());
        if (name.contains("isvoicecapable")) return Decision.handled(profile.voiceCapable());
        if (name.contains("issmscapable")) return Decision.handled(profile.smsCapable());
        if (name.contains("isemergencyonly")) return Decision.handled(profile.emergencyOnly());
        if (containsAny(name, "getallcellinfo", "getneighboringcellinfo", "getcelllocation")) {
            if (List.class.isAssignableFrom(method.getReturnType())) return Decision.handled(List.of());
            return Decision.handled(defaultValue(method.getReturnType()));
        }
        if (startsAny(name, "listen", "registertelephony", "unregistertelephony")) {
            return Decision.handled(defaultValue(method.getReturnType()));
        }
        if (startsAny(name, "set", "enable", "disable", "dial", "call", "send")) {
            throw new SecurityException("VIRTUAL_TELEPHONY_MUTATION_DENIED:" + method.getName());
        }
        return failUnsupported("telephony", method);
    }

    private Decision wifi(Method method, VirtualWifiProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (!isWifiOperation(name)) return Decision.passThrough();
        requireMode(profile.mode(), "wifi", name);
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        if (name.contains("iswifienabled")) return Decision.handled(profile.enabled());
        if (name.contains("getwifienabledstate") || name.equals("getwifistate")) {
            return Decision.handled(profile.enabled() ? 3 : 1);
        }
        if (name.contains("getconnectioninfo")) {
            return Decision.handled(FrameworkDeviceObjectFactory.wifiInfo(method.getReturnType(), profile));
        }
        if (name.contains("getscanresults")) {
            return Decision.handled(FrameworkDeviceObjectFactory.list(method, profile.scanResults(),
                    (type, value) -> FrameworkDeviceObjectFactory.wifiNetwork(
                            type, (VirtualWifiNetworkSnapshot) value)));
        }
        if (name.contains("startscan")) return Decision.handled(profile.enabled());
        if (name.contains("getdhcpinfo")) {
            return Decision.handled(FrameworkDeviceObjectFactory.dhcpInfo(method.getReturnType(), profile));
        }
        if (name.contains("getfactorymacaddresses")) {
            Object array = Array.newInstance(String.class, 1);
            Array.set(array, 0, profile.macAddress());
            return Decision.handled(array);
        }
        if (containsAny(name, "getconfigurednetworks", "getprivilegedconfigurednetworks",
                "getpasspoints", "getsuggestion")) return Decision.handled(List.of());
        if (startsAny(name, "set", "add", "remove", "enable", "disable", "connect", "disconnect",
                "reassociate", "reconnect", "save")) {
            throw new SecurityException("VIRTUAL_WIFI_MUTATION_DENIED:" + method.getName());
        }
        return failUnsupported("wifi", method);
    }

    private Decision bluetooth(Method method, Object[] arguments,
            VirtualBluetoothProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (!isBluetoothOperation(name)) return Decision.passThrough();
        requireMode(profile.mode(), "bluetooth", name);
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        if (name.equals("isenabled")) return Decision.handled(profile.enabled());
        if (name.equals("getstate")) return Decision.handled(profile.state());
        if (name.equals("getname")) return Decision.handled(profile.name());
        if (name.equals("getaddress")) return Decision.handled(profile.address());
        if (name.contains("isdiscovering")) return Decision.handled(profile.discovering());
        if (name.contains("getbondeddevices")) {
            return Decision.handled(FrameworkDeviceObjectFactory.set(method, profile.bondedDevices(),
                    (type, value) -> FrameworkDeviceObjectFactory.bluetoothDevice(
                            type, (VirtualBluetoothDeviceSnapshot) value)));
        }
        String address = firstMac(arguments);
        VirtualBluetoothDeviceSnapshot device = findBluetoothDevice(profile, address);
        if (containsAny(name, "getremotename", "getnamefor", "getalias")) {
            return Decision.handled(device == null ? null : device.name());
        }
        if (name.contains("getremotetype")) return Decision.handled(device == null ? 0 : device.type());
        if (name.contains("getbondstate")) return Decision.handled(device == null ? 10 : device.bondState());
        if (containsAny(name, "startdiscovery", "canceldiscovery")) {
            return Decision.handled(false);
        }
        if (startsAny(name, "enable", "disable", "set", "createbond", "removebond", "connect")) {
            throw new SecurityException("VIRTUAL_BLUETOOTH_MUTATION_DENIED:" + method.getName());
        }
        return failUnsupported("bluetooth", method);
    }

    private Decision sensors(Method method, Object[] arguments, VirtualSensorProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (!isSensorOperation(name)) return Decision.passThrough();
        requireMode(profile.mode(), "sensor", name);
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        int type = firstInt(arguments, -1);
        if (name.contains("getsensorlist") || name.contains("getfullsensorlist")) {
            List<VirtualSensorSnapshot> values = type <= 0 ? profile.sensors()
                    : profile.sensors().stream().filter(sensor -> sensor.type() == type).toList();
            return Decision.handled(FrameworkDeviceObjectFactory.list(method, values,
                    (targetType, value) -> FrameworkDeviceObjectFactory.sensor(
                            targetType, (VirtualSensorSnapshot) value)));
        }
        if (name.contains("getdefaultsensor")) {
            VirtualSensorSnapshot sensor = profile.sensorForType(type);
            return Decision.handled(sensor == null ? null
                    : FrameworkDeviceObjectFactory.sensor(method.getReturnType(), sensor));
        }
        if (InvocationMethodMatcher.named(name, "unregisterListener", "disableSensor", "flush")
                || InvocationMethodMatcher.startsWith(name, "unregisterListener", "disableSensor")) {
            Object listener = callback(arguments);
            if (listener != null) identity.capabilityLeases().release(
                    listener, identity.capabilityAudit(), "EXPLICIT_SENSOR_RELEASE");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (InvocationMethodMatcher.named(name, "registerListener", "registerSensor", "enableSensor")
                || InvocationMethodMatcher.startsWith(name, "registerListener", "registerSensor", "enableSensor")) {
            Object listener = callback(arguments);
            if (listener == null) return Decision.handled(falseValue(method.getReturnType()));
            VirtualSensorSnapshot sensor = profile.sensorForType(type);
            if (sensor == null && !profile.sensors().isEmpty()) sensor = profile.sensors().get(0);
            if (sensor == null) return Decision.handled(falseValue(method.getReturnType()));
            invokeCallback(listener, new String[]{"onSensorChanged", "dispatchSensorEvent"}, sensor.values());
            identity.capabilityLeases().register("sensor", listener, () -> { });
            return Decision.handled(successValue(method.getReturnType()));
        }
        return failUnsupported("sensor", method);
    }

    private static void requireMode(String mode, String service, String operation) {
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(mode)) {
            throw new SecurityException("VIRTUAL_" + service.toUpperCase(Locale.ROOT)
                    + "_BLOCKED:" + operation);
        }
    }

    private static Decision failUnsupported(String service, Method method) {
        throw new UnsupportedOperationException("VIRTUAL_" + service.toUpperCase(Locale.ROOT)
                + "_SIGNATURE_UNSUPPORTED:" + method.getName());
    }

    private static VirtualTelephonySlotSnapshot telephonySlot(
            VirtualTelephonyProfileSnapshot profile, Object[] arguments) {
        int candidate = firstInt(arguments, Integer.MIN_VALUE);
        if (candidate != Integer.MIN_VALUE) {
            VirtualTelephonySlotSnapshot subscription = profile.slotForSubscription(candidate);
            if (subscription != null && subscription.subscriptionId() == candidate) return subscription;
            VirtualTelephonySlotSnapshot slot = profile.slotForIndex(candidate);
            if (slot != null) return slot;
        }
        return profile.defaultSlot();
    }

    private static VirtualBluetoothDeviceSnapshot findBluetoothDevice(
            VirtualBluetoothProfileSnapshot profile, String address) {
        if (address == null) return null;
        for (VirtualBluetoothDeviceSnapshot value : profile.bondedDevices()) {
            if (address.equalsIgnoreCase(value.address())) return value;
        }
        for (VirtualBluetoothDeviceSnapshot value : profile.scanResults()) {
            if (address.equalsIgnoreCase(value.address())) return value;
        }
        return null;
    }

    private static Object callback(Object[] arguments) {
        if (arguments == null) return null;
        for (int index = arguments.length - 1; index >= 0; index--) {
            Object value = arguments[index];
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean || value.getClass().isEnum()) continue;
            String type = value.getClass().getName().toLowerCase(Locale.ROOT);
            if (type.contains("pendingintent") || type.contains("executor")
                    || type.contains("request") || type.contains("attribution")) continue;
            return value;
        }
        return null;
    }

    private static Class<?> locationCallbackType(Object listener) {
        for (Method method : listener.getClass().getMethods()) {
            if ((method.getName().equals("onLocationChanged") || method.getName().equals("onLocationResult"))
                    && method.getParameterCount() >= 1) return method.getParameterTypes()[0];
        }
        return Object.class;
    }

    private static void invokeCallback(Object listener, String[] names, Object... arguments) {
        for (Method method : listener.getClass().getMethods()) {
            if (!contains(names, method.getName()) || method.getParameterCount() != arguments.length) continue;
            try { method.setAccessible(true); method.invoke(listener, arguments); return; }
            catch (Throwable ignored) { com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(ignored); }
        }
    }

    private static Object successValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        return null;
    }
    private static Object falseValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return false;
        return defaultValue(type);
    }
    private static Object defaultValue(Class<?> type) {
        if (type == void.class) return null;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == float.class || type == Float.class) return 0f;
        if (type == double.class || type == Double.class) return 0d;
        if (List.class.isAssignableFrom(type)) return List.of();
        if (java.util.Set.class.isAssignableFrom(type)) return Collections.emptySet();
        return null;
    }

    private static int firstInt(Object[] values, int fallback) {
        if (values != null) for (Object value : values) if (value instanceof Integer) return (Integer) value;
        return fallback;
    }
    private static String firstMac(Object[] values) {
        if (values != null) for (Object value : values) {
            if (value instanceof String text && text.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return text;
        }
        return null;
    }
    private static boolean isLocationOperation(String name) {
        return containsAny(name, "location", "provider", "gnss", "gps", "nmea", "geofence");
    }
    private static boolean isTelephonyOperation(String name) {
        return containsAny(name, "deviceid", "imei", "meid", "subscriber", "sim", "network",
                "phone", "cell", "line1", "msisdn", "telephony", "userdata", "roaming",
                "subscription", "subinfo", "subid", "slotindex", "voicecapable",
                "smscapable", "emergency", "dataenabled");
    }
    private static boolean isWifiOperation(String name) {
        return containsAny(name, "wifi", "scan", "connectioninfo", "dhcp", "configurednetwork",
                "passpoint", "suggestion", "factorymac", "reassociate", "reconnect");
    }
    private static boolean isBluetoothOperation(String name) {
        return containsAny(name, "bluetooth", "discover", "bond", "remote", "adapter", "address",
                "getname", "getstate", "isenabled", "enable", "disable");
    }
    private static boolean isSensorOperation(String name) {
        return containsAny(name, "sensor", "listener", "flush", "trigger");
    }
    private static boolean isCleanup(String name) {
        return startsAny(name, "remove", "unregister", "cancel", "stop");
    }
    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
    private static boolean startsAny(String value, String... needles) {
        for (String needle : needles) if (value.startsWith(needle)) return true;
        return false;
    }
    private static boolean contains(String[] values, String value) {
        for (String item : values) if (item.equals(value)) return true;
        return false;
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    record Decision(boolean handled, Object result) {
        static Decision handled(Object value) { return new Decision(true, value); }
        static Decision passThrough() { return new Decision(false, null); }
    }
}
