package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.contract.InvocationMethodMatcher;

import com.warden.controlledsandbox.contract.VirtualBluetoothDeviceSnapshot;
import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCellInfoSnapshot;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Deterministic result projection for device-facing framework services. */
final class DeviceServiceInvocationInterceptor {
    private static final ScheduledExecutorService LOCATION_CALLBACK_EXECUTOR =
            Executors.newScheduledThreadPool(1, new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "controlled-sandbox-location");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private static volatile boolean LOCATION_REFLECTION_LOGGED;
    private static volatile boolean LOCATION_FIELDS_LOGGED;
    private static volatile boolean LOCATION_STATE_LOGGED;
    private final GuestIdentity identity;
    private final String service;
    private final Map<Object, ScheduledFuture<?>> locationCallbacks = new IdentityHashMap<>();

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
        if (isCleanup(name)) {
            Object listener = callback(arguments);
            if (listener != null) {
                identity.capabilityLeases().release(listener, identity.capabilityAudit(),
                        "EXPLICIT_LOCATION_RELEASE");
            }
            return Decision.handled(defaultValue(method.getReturnType()));
        }
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
                invokeLocationCallback(listener, profile);
                return Decision.handled(null);
            }
            return Decision.handled(FrameworkDeviceObjectFactory.location(method.getReturnType(), profile));
        }
        if (containsAny(name, "requestlocationupdates", "registerlocationlistener")) {
            Object listener = callback(arguments);
            if (listener == null) {
                throw new UnsupportedOperationException("VIRTUAL_LOCATION_PENDING_INTENT_UNSUPPORTED");
            }
            android.util.Log.i("CS_LOCATION_CALL", "method=" + method.getName()
                    + " listener=" + listener.getClass().getName()
                    + " callbacks=" + callbackMethodNames(listener));
            invokeLocationCallback(listener, profile);
            long intervalMs = Math.max(50L, profile.minimumUpdateIntervalMs() > 0L
                    ? profile.minimumUpdateIntervalMs() : 1000L);
            ScheduledFuture<?> future = LOCATION_CALLBACK_EXECUTOR.scheduleAtFixedRate(() -> {
                invokeLocationCallback(listener, profile);
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            synchronized (locationCallbacks) {
                ScheduledFuture<?> previous = locationCallbacks.put(listener, future);
                if (previous != null) previous.cancel(false);
            }
            identity.capabilityLeases().register("location", listener, () -> {
                ScheduledFuture<?> scheduled;
                synchronized (locationCallbacks) { scheduled = locationCallbacks.remove(listener); }
                if (scheduled != null) scheduled.cancel(false);
            });
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
        if (name.contains("getnetworkoperator")) {
            android.util.Log.i("CS_TELEPHONY_CELL", "method=" + method.getName()
                    + " networkOperator=" + (slot == null ? "<null>" : slot.networkOperator())
                    + " slots=" + profile.slots().size());
            return Decision.handled(slot == null ? "" : slot.networkOperator());
        }
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
            android.util.Log.i("CS_TELEPHONY_CELL", "method=" + method.getName()
                    + " generic=" + method.getGenericReturnType()
                    + " profileCells=" + profile.cells().size());
            if (List.class.isAssignableFrom(method.getReturnType())) {
                Object result = FrameworkDeviceObjectFactory.list(method, profile.cells(),
                        (type, value) -> FrameworkDeviceObjectFactory.cellInfo(
                                type, (VirtualCellInfoSnapshot) value));
                android.util.Log.i("CS_TELEPHONY_CELL", "return=" + result.getClass().getName()
                        + " size=" + ((List<?>) result).size());
                return Decision.handled(result);
            }
            if (!profile.cells().isEmpty()) {
                return Decision.handled(FrameworkDeviceObjectFactory.cellInfo(
                        method.getReturnType(), profile.cells().get(0)));
            }
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
        // BluetoothAdapter initializes its optional binder through these lifecycle calls.
        // A virtual profile without a host-backed adapter must return an absent binder,
        // rather than exposing the host service or throwing on an otherwise harmless probe.
        if (name.equals("registeradapter") || name.equals("unregisteradapter")) {
            return Decision.handled(null);
        }
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
                    : profile.sensors().stream().filter(sensor -> sensor.type() == type)
                            .collect(java.util.stream.Collectors.toList());
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

    /**
     * Android R+ delivers a LocationListener through the hidden ILocationListener Binder stub.
     * Its callback is onLocationChanged(List<Location>, IRemoteCallback), while older releases
     * and direct test transports use onLocationChanged(Location) or onLocationResult(...).
     * Adapt the value at this boundary so the Guest's public LocationListener still receives a
     * real Location instead of a Binder-shaped implementation detail.
     */
    private static void invokeLocationCallback(Object listener,
                                                VirtualLocationProfileSnapshot profile) {
        if (invokeKnownLocationTransport(listener, profile)) return;
        if (invokeLocationCallbackTarget(listener, profile)) return;
        Object registeredListener = findRegisteredLocationListener(listener);
        if (registeredListener != null && invokeLocationCallbackTarget(registeredListener, profile)) {
            android.util.Log.i("CS_LOCATION_CALLBACK", "registered listener delivered="
                    + registeredListener.getClass().getName());
            return;
        }
        // API 31/32 framework builds have shipped variants where the generated hidden AIDL
        // callback is not exposed by reflection on LocationListenerTransport, even though the
        // transport owns the public LocationListener in a private mListener field.  Reach that
        // guest-owned listener as the final adapter boundary; never inspect or forward a Host
        // listener.
        Class<?> type = listener.getClass();
        if (!LOCATION_FIELDS_LOGGED) {
            LOCATION_FIELDS_LOGGED = true;
            StringBuilder fields = new StringBuilder();
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                fields.append(current.getName()).append(':')
                        .append(java.util.Arrays.toString(current.getDeclaredFields())).append(';');
            }
            android.util.Log.i("CS_LOCATION_REFLECTION", "transportFields=" + fields);
        }
        while (type != null) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                String fieldName = field.getName().toLowerCase(Locale.ROOT);
                if (!fieldName.contains("listener")) continue;
                try {
                    field.setAccessible(true);
                    Object nested = field.get(listener);
                    if (nested != null && nested != listener
                            && invokeLocationCallbackTarget(nested, profile)) {
                        android.util.Log.i("CS_LOCATION_CALLBACK", "nested listener delivered="
                                + nested.getClass().getName());
                        return;
                    }
                } catch (Throwable error) {
                    com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                            .rethrowIfFatal(error);
                }
            }
            type = type.getSuperclass();
        }
        for (Method method : callbackMethods(listener)) {
            if (!(method.getName().equals("onLocationChanged")
                    || method.getName().equals("onLocationResult")
                    || method.getName().equals("accept"))) continue;
            Object[] arguments;
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1) {
                arguments = new Object[]{FrameworkDeviceObjectFactory.location(parameterTypes[0], profile)};
            } else if (parameterTypes.length == 2
                    && List.class.isAssignableFrom(parameterTypes[0])) {
                Object location = FrameworkDeviceObjectFactory.location(
                        android.location.Location.class, profile);
                arguments = new Object[]{List.of(location), null};
            } else {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(listener, arguments);
                return;
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
                android.util.Log.w("CS_LOCATION_CALLBACK", "invoke failed listener="
                        + listener.getClass().getName() + " method=" + method.getName()
                        + " error=" + error.getClass().getName());
            }
        }
        android.util.Log.w("CS_LOCATION_CALLBACK", "no compatible callback listener="
                + listener.getClass().getName());
    }

    private static boolean invokeKnownLocationTransport(Object target,
                                                        VirtualLocationProfileSnapshot profile) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Class<?> remoteCallback = Class.forName("android.os.IRemoteCallback");
                Method method = type.getDeclaredMethod("onLocationChanged", List.class,
                        remoteCallback);
                method.setAccessible(true);
                Object location = FrameworkDeviceObjectFactory.location(
                        android.location.Location.class, profile);
                method.invoke(target, List.of(location), null);
                return true;
            } catch (NoSuchMethodException missingBatchCallback) {
                // Try the older single-location framework contract below.
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                        .rethrowIfFatal(error);
                android.util.Log.w("CS_LOCATION_CALLBACK", "known transport invoke failed="
                        + error.getClass().getName());
                break;
            }
            try {
                Method method = type.getDeclaredMethod("onLocationChanged",
                        android.location.Location.class);
                method.setAccessible(true);
                method.invoke(target, FrameworkDeviceObjectFactory.location(
                        android.location.Location.class, profile));
                return true;
            } catch (NoSuchMethodException missingSingleCallback) {
                type = type.getSuperclass();
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                        .rethrowIfFatal(error);
                android.util.Log.w("CS_LOCATION_CALLBACK", "known transport invoke failed="
                        + error.getClass().getName());
                return false;
            }
        }
        return false;
    }

    private static Object findRegisteredLocationListener(Object transport) {
        try {
            Class<?> manager = Class.forName("android.location.LocationManager");
            for (java.lang.reflect.Field field : manager.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                Object match = findListenerInContainer(value, transport);
                if (match != null) return match;
                if (!LOCATION_STATE_LOGGED && value instanceof java.util.Map) {
                    LOCATION_STATE_LOGGED = true;
                    android.util.Log.i("CS_LOCATION_REFLECTION", "managerStateField="
                            + field.getName() + " type=" + field.getType().getName()
                            + " size=" + ((java.util.Map<?, ?>) value).size());
                }
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            android.util.Log.w("CS_LOCATION_REFLECTION", "manager state lookup failed error="
                    + error.getClass().getName());
        }
        return null;
    }

    private static Object findListenerInContainer(Object container, Object transport) {
        if (container == null) return null;
        if (container instanceof java.util.Map<?, ?> map) {
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = dereference(entry.getKey());
                Object value = dereference(entry.getValue());
                if (value == transport && key != transport && key != null) return key;
                if (key == transport && value != null && value != transport) return value;
            }
        } else if (container instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                Object candidate = dereference(value);
                if (candidate == transport) return null;
            }
        }
        return null;
    }

    private static Object dereference(Object value) {
        if (value instanceof java.lang.ref.Reference<?> reference) return reference.get();
        return value;
    }

    private static boolean invokeLocationCallbackTarget(Object target,
                                                        VirtualLocationProfileSnapshot profile) {
        for (Method method : callbackMethods(target)) {
            if (!(method.getName().equals("onLocationChanged")
                    || method.getName().equals("onLocationResult")
                    || method.getName().equals("accept"))) continue;
            Object[] arguments;
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1) {
                arguments = new Object[]{FrameworkDeviceObjectFactory.location(
                        parameterTypes[0], profile)};
            } else if (parameterTypes.length == 2
                    && List.class.isAssignableFrom(parameterTypes[0])) {
                Object location = FrameworkDeviceObjectFactory.location(
                        android.location.Location.class, profile);
                arguments = new Object[]{List.of(location), null};
            } else {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(target, arguments);
                return true;
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                        .rethrowIfFatal(error);
                android.util.Log.w("CS_LOCATION_CALLBACK", "invoke failed listener="
                        + target.getClass().getName() + " method=" + method.getName()
                        + " error=" + error.getClass().getName());
            }
        }
        return false;
    }

    private static void invokeCallback(Object listener, String[] names, Object... arguments) {
        boolean matched = false;
        for (Method method : callbackMethods(listener)) {
            if (!contains(names, method.getName()) || method.getParameterCount() != arguments.length) continue;
            matched = true;
            try { method.setAccessible(true); method.invoke(listener, arguments); return; }
            catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
                android.util.Log.w("CS_LOCATION_CALLBACK", "invoke failed listener="
                        + listener.getClass().getName() + " method=" + method.getName()
                        + " error=" + error.getClass().getName());
            }
        }
        if (!matched) android.util.Log.w("CS_LOCATION_CALLBACK", "no compatible callback listener="
                + listener.getClass().getName() + " names=" + java.util.Arrays.toString(names)
                + " args=" + arguments.length + " methods=" + callbackMethodNames(listener));
    }

    private static List<Method> callbackMethods(Object listener) {
        List<Method> methods = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Class<?> type = listener.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                String key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
                if (seen.add(key)) methods.add(method);
            }
            addInterfaceMethods(type.getInterfaces(), methods, seen);
            type = type.getSuperclass();
        }
        // AIDL Stub implementations on some Android releases expose the callback only through
        // the generated public interface.  getMethods() is the reliable final view of inherited
        // interface methods; retain the bounded name/arity filtering at invocation time.
        for (Method method : listener.getClass().getMethods()) {
            String key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
            if (seen.add(key)) methods.add(method);
        }
        // The generated AIDL Stub can hide the interface from reflection on the concrete class
        // under the framework class loader.  Load the stable platform contract explicitly; the
        // Method still dispatches to the concrete Stub implementation.
        try {
            Class<?> locationListener = Class.forName("android.location.ILocationListener");
            if (!LOCATION_REFLECTION_LOGGED) {
                LOCATION_REFLECTION_LOGGED = true;
                android.util.Log.i("CS_LOCATION_REFLECTION", "contract="
                        + locationListener.getName() + " methods="
                        + java.util.Arrays.toString(locationListener.getDeclaredMethods())
                        + " assignable=" + locationListener.isAssignableFrom(listener.getClass()));
            }
            addInterfaceMethods(new Class<?>[]{locationListener}, methods, seen);
        } catch (Throwable ignored) {
            android.util.Log.w("CS_LOCATION_REFLECTION", "contract lookup failed"
                    + " error=" + ignored.getClass().getName());
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(ignored);
        }
        return methods;
    }

    private static void addInterfaceMethods(Class<?>[] interfaces, List<Method> methods,
                                            java.util.Set<String> seen) {
        for (Class<?> iface : interfaces) {
            for (Method method : iface.getDeclaredMethods()) {
                String key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
                if (seen.add(key)) methods.add(method);
            }
            for (Method method : iface.getMethods()) {
                String key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
                if (seen.add(key)) methods.add(method);
            }
            addInterfaceMethods(iface.getInterfaces(), methods, seen);
        }
    }

    private static String callbackMethodNames(Object listener) {
        StringBuilder names = new StringBuilder("[");
        for (Method method : callbackMethods(listener)) {
            if (names.length() > 1) names.append(',');
            names.append(method.getName()).append('/').append(method.getParameterCount());
        }
        return names.append(']').toString();
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
