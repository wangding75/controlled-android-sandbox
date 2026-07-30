package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
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
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-side deterministic projection tests for all M5-T8 device-service domains. */
public final class DeviceServiceVirtualizationSelfTest {
    public static void main(String[] args) {
        VirtualDeviceServiceProfileSnapshot profile = profile(VirtualLocationProfileSnapshot.MODE_STATIC);
        GuestIdentity identity = identity(profile);
        testLocation(identity);
        testTelephony(identity);
        testSubscription(identity);
        testWifi(identity);
        testBluetooth(identity);
        testSensors(identity);
        testBlockedLocation();
        testHostLocationPassThrough();
        System.out.println("PASS M5-T8 device-service virtualization self-test");
    }

    private static void testLocation(GuestIdentity identity) {
        FakeLocationDelegate delegate = new FakeLocationDelegate();
        LocationApi api = proxy(LocationApi.class, delegate, identity, "location");
        FakeLocation location = api.getLastLocation("guest.pkg");
        require(location != null && location.latitude == 31.2304d && location.longitude == 121.4737d,
                "static location replaces host result");
        require(delegate.calls == 0, "virtual location does not call host delegate");
        LocationListener listener = new LocationListener();
        api.requestLocationUpdates("gps", listener, "guest.pkg");
        require(listener.updates == 1 && identity.capabilityLeases().activeCount("location") == 1,
                "location update callback and lease are virtualized");
        api.removeUpdates(listener);
        require(identity.capabilityLeases().activeCount("location") == 0,
                "virtual location listener is released without host call");
    }

    private static void testTelephony(GuestIdentity identity) {
        FakeTelephonyDelegate delegate = new FakeTelephonyDelegate();
        TelephonyApi api = proxy(TelephonyApi.class, delegate, identity, "telephony");
        require("490154203237518".equals(api.getImei(0)), "IMEI projected by slot");
        require("001011234567890".equals(api.getSubscriberId(1001)), "IMSI projected by subscription");
        require("00101".equals(api.getNetworkOperatorForPhone(0)), "operator projected");
        require(api.getPhoneCount() == 1 && delegate.calls == 0,
                "telephony capability and host isolation projected");
        boolean mutationDenied = false;
        try { api.setDataEnabled(true); }
        catch (SecurityException expected) { mutationDenied = true; }
        require(mutationDenied, "telephony mutation denied");
    }


    private static void testSubscription(GuestIdentity identity) {
        FakeSubscriptionDelegate delegate = new FakeSubscriptionDelegate();
        SubscriptionApi api = proxy(SubscriptionApi.class, delegate, identity, "subscription");
        List<FakeSubscriptionInfo> subscriptions = api.getActiveSubscriptionInfoList("guest.pkg");
        require(subscriptions.size() == 1 && subscriptions.get(0).subscriptionId == 1001
                        && subscriptions.get(0).slotIndex == 0,
                "subscription list projected from virtual slots");
        require(api.getActiveSubIdList()[0] == 1001 && api.isActiveSubId(1001)
                        && api.getSlotIndex(1001) == 0 && delegate.calls == 0,
                "subscription identifiers projected without host delegate");
    }

    private static void testWifi(GuestIdentity identity) {
        FakeWifiDelegate delegate = new FakeWifiDelegate();
        WifiApi api = proxy(WifiApi.class, delegate, identity, "wifi");
        FakeWifiInfo info = api.getConnectionInfo("guest.pkg");
        require("SandboxNet".equals(info.ssid) && "02:11:22:33:44:55".equals(info.macAddress),
                "Wi-Fi connection identity projected");
        List<FakeScanResult> scans = api.getScanResults("guest.pkg");
        require(scans.size() == 1 && "02:AA:BB:CC:DD:EE".equals(scans.get(0).bssid),
                "Wi-Fi scan results projected");
        require(api.getFactoryMacAddresses()[0].equals("02:11:22:33:44:55") && delegate.calls == 0,
                "Wi-Fi host identity not called");
    }

    private static void testBluetooth(GuestIdentity identity) {
        FakeBluetoothDelegate delegate = new FakeBluetoothDelegate();
        BluetoothApi api = proxy(BluetoothApi.class, delegate, identity, "bluetooth");
        require("02:66:77:88:99:AA".equals(api.getAddress()), "adapter address projected");
        Set<FakeBluetoothDevice> bonded = api.getBondedDevices();
        require(bonded.size() == 1 && bonded.iterator().next().address.equals("02:00:00:00:00:01"),
                "bonded devices projected");
        require("Keyboard".equals(api.getRemoteName("02:00:00:00:00:01")) && delegate.calls == 0,
                "Bluetooth remote identity projected without host delegate");
    }

    private static void testSensors(GuestIdentity identity) {
        FakeSensorDelegate delegate = new FakeSensorDelegate();
        SensorApi api = proxy(SensorApi.class, delegate, identity, "sensor");
        List<FakeSensor> sensors = api.getSensorList(1);
        require(sensors.size() == 1 && sensors.get(0).type == 1,
                "sensor catalog projected by type");
        SensorListener listener = new SensorListener();
        require(api.registerListener(listener, 1), "virtual sensor listener accepted");
        require(listener.events == 1 && listener.last.length == 3 && listener.last[2] == 9.80665f,
                "virtual sensor sample delivered");
        api.unregisterListener(listener);
        require(identity.capabilityLeases().activeCount("sensor") == 0,
                "unregister sensor listener releases rather than re-registers the lease");
        require(delegate.calls == 0, "sensor virtual path does not call delegate");
    }

    private static void testBlockedLocation() {
        GuestIdentity identity = identity(profile(VirtualLocationProfileSnapshot.MODE_BLOCKED));
        LocationApi api = proxy(LocationApi.class, new FakeLocationDelegate(), identity, "location");
        boolean blocked = false;
        try { api.getLastLocation("guest.pkg"); }
        catch (SecurityException expected) { blocked = expected.getMessage().contains("VIRTUAL_LOCATION_BLOCKED"); }
        require(blocked, "blocked location fails closed");
    }


    private static void testHostLocationPassThrough() {
        GuestIdentity identity = identity(profile(VirtualLocationProfileSnapshot.MODE_HOST));
        FakeLocationDelegate delegate = new FakeLocationDelegate();
        LocationApi api = proxy(LocationApi.class, delegate, identity, "location");
        api.getLastLocation("guest.pkg");
        api.removeUpdates(new LocationListener());
        require(delegate.calls == 2, "HOST location mode passes query and cleanup to host delegate");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T delegate, GuestIdentity identity, String service) {
        return (T) Proxy.newProxyInstance(DeviceServiceVirtualizationSelfTest.class.getClassLoader(),
                new Class<?>[]{type}, new SystemServiceInvocationHandler(delegate, identity, service));
    }

    private static GuestIdentity identity(VirtualDeviceServiceProfileSnapshot profile) {
        Set<String> declared = Set.of("android.permission.ACCESS_FINE_LOCATION");
        ApplicationInfo info = new ApplicationInfo(); info.packageName = "guest.pkg"; info.uid = 12001;
        return new GuestIdentity("guest.pkg", 12001, info, declared, "host.pkg", 10001,
                new VirtualPackageMetadata("guest.pkg", "", info, List.of()), "guest.pkg", 0, 7L,
                new VirtualPermissionPolicy(declared, Map.of(), declared),
                new SandboxAppOpsPolicy(Map.of("android:fine_location", "ALLOWED")),
                event -> { }, new CapabilityLeaseRegistry(), new VirtualSystemServiceState(profile),
                "revision-m5-t8");
    }

    private static VirtualDeviceServiceProfileSnapshot profile(String locationMode) {
        VirtualLocationProfileSnapshot location = new VirtualLocationProfileSnapshot(locationMode, "gps",
                VirtualLocationProfileSnapshot.MODE_STATIC.equals(locationMode), 31.2304d, 121.4737d,
                10d, 5f, 0f, 0f, 100L, 200L, 1000L, true, 12, 8, "$GPGGA,test");
        VirtualDeviceIdentitySnapshot device = new VirtualDeviceIdentitySnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, "0123456789abcdef", "SERIAL123",
                "123e4567-e89b-12d3-a456-426614174000", true,
                "223e4567-e89b-12d3-a456-426614174000", "Sandbox", "Sandbox",
                "Virtual", "sandbox", "sandbox", "sandbox/fingerprint", "virtual", "virtual");
        VirtualTelephonySlotSnapshot slot = new VirtualTelephonySlotSnapshot(0, 1001,
                "490154203237518", "A0000000000000", "001011234567890",
                "8901011234567890123", "", "00101", "00101", "us", "us",
                "Sandbox Carrier", 1, 5, 13, 13, true, false);
        VirtualTelephonyProfileSnapshot telephony = new VirtualTelephonyProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, 1001, 1001, true, true, false,
                List.of(slot));
        VirtualWifiNetworkSnapshot network = new VirtualWifiNetworkSnapshot("SandboxNet",
                "02:AA:BB:CC:DD:EE", "[WPA2-PSK-CCMP][ESS]", 5180, -45, false);
        VirtualWifiProfileSnapshot wifi = new VirtualWifiProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, true, "SandboxNet",
                "02:AA:BB:CC:DD:EE", "02:11:22:33:44:55", 0x0201580a, 7,
                433, -45, 5180, false, false, List.of(network));
        VirtualBluetoothDeviceSnapshot bonded = new VirtualBluetoothDeviceSnapshot(
                "02:00:00:00:00:01", "Keyboard", 1, 12, -30, List.of());
        VirtualBluetoothProfileSnapshot bluetooth = new VirtualBluetoothProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, true, 12, "Sandbox BT",
                "02:66:77:88:99:AA", false, List.of(bonded), List.of());
        VirtualSensorSnapshot accelerometer = new VirtualSensorSnapshot(1, 1,
                "Virtual Accelerometer", "Sandbox", 1, 40f, 0.001f, 0.1f,
                10000, 1000000, 0, false, false, new float[]{0f, 0f, 9.80665f}, 3);
        VirtualSensorProfileSnapshot sensors = new VirtualSensorProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, 60, List.of(accelerometer));
        return new VirtualDeviceServiceProfileSnapshot(1L, 1L, location, device,
                telephony, wifi, bluetooth, sensors);
    }

    interface LocationApi {
        FakeLocation getLastLocation(String packageName);
        void requestLocationUpdates(String provider, LocationListener listener, String packageName);
        void removeUpdates(LocationListener listener);
    }
    static final class FakeLocationDelegate implements LocationApi {
        int calls;
        public FakeLocation getLastLocation(String packageName) { calls++; return new FakeLocation(); }
        public void requestLocationUpdates(String provider, LocationListener listener, String packageName) { calls++; }
        public void removeUpdates(LocationListener listener) { calls++; }
    }
    public static final class FakeLocation {
        double latitude; double longitude; float accuracy;
        public FakeLocation() { }
        public void setLatitude(double value) { latitude = value; }
        public void setLongitude(double value) { longitude = value; }
        public void setAccuracy(float value) { accuracy = value; }
        public void setAltitude(double ignored) { }
        public void setSpeed(float ignored) { }
        public void setBearing(float ignored) { }
        public void setTime(long ignored) { }
        public void setElapsedRealtimeNanos(long ignored) { }
        public void setMock(boolean ignored) { }
    }
    public static final class LocationListener {
        int updates;
        public void onLocationChanged(FakeLocation location) { if (location != null) updates++; }
    }

    interface TelephonyApi {
        String getImei(int slotIndex); String getSubscriberId(int subscriptionId);
        String getNetworkOperatorForPhone(int slotIndex); int getPhoneCount();
        void setDataEnabled(boolean enabled);
    }
    static final class FakeTelephonyDelegate implements TelephonyApi {
        int calls;
        public String getImei(int slotIndex) { calls++; return "host-imei"; }
        public String getSubscriberId(int subscriptionId) { calls++; return "host-imsi"; }
        public String getNetworkOperatorForPhone(int slotIndex) { calls++; return "host"; }
        public int getPhoneCount() { calls++; return 9; }
        public void setDataEnabled(boolean enabled) { calls++; }
    }


    interface SubscriptionApi {
        List<FakeSubscriptionInfo> getActiveSubscriptionInfoList(String packageName);
        int[] getActiveSubIdList(); boolean isActiveSubId(int subscriptionId);
        int getSlotIndex(int subscriptionId);
    }
    static final class FakeSubscriptionDelegate implements SubscriptionApi {
        int calls;
        public List<FakeSubscriptionInfo> getActiveSubscriptionInfoList(String packageName) {
            calls++; return List.of();
        }
        public int[] getActiveSubIdList() { calls++; return new int[0]; }
        public boolean isActiveSubId(int subscriptionId) { calls++; return false; }
        public int getSlotIndex(int subscriptionId) { calls++; return -1; }
    }
    public static final class FakeSubscriptionInfo {
        int subscriptionId; int slotIndex; String iccId; String carrierName;
        public FakeSubscriptionInfo() { }
        public void setSubscriptionId(int value) { subscriptionId = value; }
        public void setSimSlotIndex(int value) { slotIndex = value; }
        public void setIccId(String value) { iccId = value; }
        public void setCarrierName(String value) { carrierName = value; }
        public void setDisplayName(String ignored) { }
        public void setNumber(String ignored) { }
        public void setCountryIso(String ignored) { }
        public void setDataRoaming(int ignored) { }
        public void setCardId(String ignored) { }
    }

    interface WifiApi {
        FakeWifiInfo getConnectionInfo(String packageName);
        List<FakeScanResult> getScanResults(String packageName);
        String[] getFactoryMacAddresses();
    }
    static final class FakeWifiDelegate implements WifiApi {
        int calls;
        public FakeWifiInfo getConnectionInfo(String packageName) { calls++; return new FakeWifiInfo(); }
        public List<FakeScanResult> getScanResults(String packageName) { calls++; return List.of(); }
        public String[] getFactoryMacAddresses() { calls++; return new String[]{"host"}; }
    }
    public static final class FakeWifiInfo {
        String ssid; String bssid; String macAddress; int networkId; int rssi; int linkSpeed; int frequency;
        public FakeWifiInfo() { }
        public void setSSID(String value) { ssid = value; }
        public void setBSSID(String value) { bssid = value; }
        public void setMacAddress(String value) { macAddress = value; }
        public void setNetworkId(int value) { networkId = value; }
        public void setRssi(int value) { rssi = value; }
        public void setLinkSpeed(int value) { linkSpeed = value; }
        public void setFrequency(int value) { frequency = value; }
        public void setHiddenSSID(boolean ignored) { }
        public void setMeteredHint(boolean ignored) { }
    }
    public static final class FakeScanResult {
        String ssid; String bssid; int frequency; int level;
        public FakeScanResult() { }
        public void setSSID(String value) { ssid = value; }
        public void setBSSID(String value) { bssid = value; }
        public void setCapabilities(String ignored) { }
        public void setFrequency(int value) { frequency = value; }
        public void setLevel(int value) { level = value; }
    }

    interface BluetoothApi {
        String getAddress(); Set<FakeBluetoothDevice> getBondedDevices(); String getRemoteName(String address);
    }
    static final class FakeBluetoothDelegate implements BluetoothApi {
        int calls;
        public String getAddress() { calls++; return "host"; }
        public Set<FakeBluetoothDevice> getBondedDevices() { calls++; return Set.of(); }
        public String getRemoteName(String address) { calls++; return "host"; }
    }
    public static final class FakeBluetoothDevice {
        String address; String name; int type; int bondState;
        public FakeBluetoothDevice(String address) { this.address = address; }
        public void setAddress(String value) { address = value; }
        public void setName(String value) { name = value; }
        public void setType(int value) { type = value; }
        public void setBondState(int value) { bondState = value; }
    }

    interface SensorApi {
        List<FakeSensor> getSensorList(int type); FakeSensor getDefaultSensor(int type);
        boolean registerListener(SensorListener listener, int type); void unregisterListener(SensorListener listener);
    }
    static final class FakeSensorDelegate implements SensorApi {
        int calls;
        public List<FakeSensor> getSensorList(int type) { calls++; return List.of(); }
        public FakeSensor getDefaultSensor(int type) { calls++; return null; }
        public boolean registerListener(SensorListener listener, int type) { calls++; return false; }
        public void unregisterListener(SensorListener listener) { calls++; }
    }
    public static final class FakeSensor {
        int type; int handle; String name;
        public FakeSensor() { }
        public void setType(int value) { type = value; }
        public void setHandle(int value) { handle = value; }
        public void setName(String value) { name = value; }
        public void setVendor(String ignored) { }
        public void setVersion(int ignored) { }
        public void setMaxRange(float ignored) { }
        public void setResolution(float ignored) { }
        public void setPower(float ignored) { }
        public void setMinDelay(int ignored) { }
        public void setMaxDelay(int ignored) { }
    }
    public static final class SensorListener {
        int events; float[] last = new float[0];
        public void onSensorChanged(float[] values) { events++; last = values.clone(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
