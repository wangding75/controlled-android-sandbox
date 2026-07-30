package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeviceServiceProxyReadinessSelfTest {
    public static void main(String[] args) {
        Map<String, Boolean> installed = new LinkedHashMap<>();
        for (String name : List.of("location", "deviceIdentity", "settingsIdentity", "telephony",
                "phoneSubInfo", "telephonyRegistry", "subscription", "wifi", "wifiScanner",
                "bluetooth", "sensorCatalog")) installed.put(name, true);
        DeviceServiceProxyReadiness.require(installed, profile(VirtualLocationProfileSnapshot.MODE_STATIC));
        installed.put("settingsIdentity", false);
        boolean blocked = false;
        try { DeviceServiceProxyReadiness.require(installed,
                profile(VirtualLocationProfileSnapshot.MODE_STATIC)); }
        catch (IllegalStateException expected) {
            blocked = expected.getMessage().contains("settingsIdentity");
        }
        require(blocked, "missing identity hook blocks launch");
        DeviceServiceProxyReadiness.require(Map.of(), profile(VirtualLocationProfileSnapshot.MODE_HOST));
        System.out.println("PASS M5-T8 device-service proxy readiness self-test");
    }

    private static VirtualDeviceServiceProfileSnapshot profile(String mode) {
        VirtualLocationProfileSnapshot location = new VirtualLocationProfileSnapshot(mode, "gps", false,
                0, 0, 0, 0, 0, 0, 0, 0, 1000, false, 0, 0, "");
        VirtualDeviceIdentitySnapshot identity = new VirtualDeviceIdentitySnapshot(mode,
                mode.equals(VirtualLocationProfileSnapshot.MODE_STATIC) ? "0123456789abcdef" : "",
                "", "", false, "", "", "", "", "", "", "", "", "");
        VirtualTelephonyProfileSnapshot telephony = new VirtualTelephonyProfileSnapshot(mode,
                -1, -1, false, false, false, List.of());
        VirtualWifiProfileSnapshot wifi = new VirtualWifiProfileSnapshot(mode, false, "", "", "",
                0, -1, 0, -127, 0, false, false, List.of());
        VirtualBluetoothProfileSnapshot bluetooth = new VirtualBluetoothProfileSnapshot(mode,
                false, 10, "", "", false, List.of(), List.of());
        VirtualSensorProfileSnapshot sensors = new VirtualSensorProfileSnapshot(mode, 60, List.of());
        return new VirtualDeviceServiceProfileSnapshot(1, 1, location, identity,
                telephony, wifi, bluetooth, sensors);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
