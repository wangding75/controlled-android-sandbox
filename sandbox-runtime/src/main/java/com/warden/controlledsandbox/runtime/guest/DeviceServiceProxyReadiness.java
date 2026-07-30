package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fail-closed launch gate preventing configured virtual device domains from leaking host values. */
final class DeviceServiceProxyReadiness {
    private DeviceServiceProxyReadiness() { }

    static void require(Map<String, Boolean> installed,
            VirtualDeviceServiceProfileSnapshot profile) {
        if (installed == null || profile == null) {
            throw new IllegalStateException("VIRTUAL_DEVICE_SERVICE_READINESS_INPUT_REQUIRED");
        }
        List<String> missing = new ArrayList<>();
        requireDomain(profile.location().mode(), installed, missing, "location");
        requireDomain(profile.identity().mode(), installed, missing, "deviceIdentity", "settingsIdentity");
        requireDomain(profile.telephony().mode(), installed, missing,
                "telephony", "phoneSubInfo", "telephonyRegistry", "subscription");
        requireDomain(profile.wifi().mode(), installed, missing, "wifi", "wifiScanner");
        requireDomain(profile.bluetooth().mode(), installed, missing, "bluetooth");
        requireDomain(profile.sensors().mode(), installed, missing, "sensorCatalog");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("VIRTUAL_DEVICE_SERVICE_PROXY_REQUIRED:"
                    + String.join(",", missing));
        }
    }

    private static void requireDomain(String mode, Map<String, Boolean> installed,
            List<String> missing, String... hooks) {
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(mode)) return;
        for (String hook : hooks) if (!Boolean.TRUE.equals(installed.get(hook))) missing.add(hook);
    }
}
