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
        require(installed, null, null, profile);
    }

    static void require(Map<String, Boolean> installed, Map<String, String> bindingDetails,
            VirtualDeviceServiceProfileSnapshot profile) {
        require(installed, bindingDetails, null, profile);
    }

    static void require(Map<String, Boolean> installed, Map<String, String> bindingDetails,
            Map<String, String> failures, VirtualDeviceServiceProfileSnapshot profile) {
        if (installed == null || profile == null) {
            throw new IllegalStateException("VIRTUAL_DEVICE_SERVICE_READINESS_INPUT_REQUIRED");
        }
        List<String> missing = new ArrayList<>();
        requireDomain(profile.location().mode(), installed, bindingDetails, missing, "location");
        requireDomain(profile.identity().mode(), installed, bindingDetails, missing,
                "deviceIdentity", "settingsIdentity");
        requireDomain(profile.telephony().mode(), installed, bindingDetails, missing,
                "telephony", "phoneSubInfo", "telephonyRegistry", "subscription");
        requireDomain(profile.wifi().mode(), installed, bindingDetails, missing, "wifi", "wifiScanner");
        requireDomain(profile.bluetooth().mode(), installed, bindingDetails, missing, "bluetooth");
        requireDomain(profile.sensors().mode(), installed, bindingDetails, missing, "sensorCatalog");
        if (!missing.isEmpty()) {
            StringBuilder message = new StringBuilder("VIRTUAL_DEVICE_SERVICE_PROXY_REQUIRED:")
                    .append(String.join(",", missing));
            if (failures != null && !failures.isEmpty()) {
                message.append(" failures=");
                boolean first = true;
                for (String name : missing) {
                    String key = name.endsWith("(binding-contract)")
                            ? name.substring(0, name.length() - "(binding-contract)".length()) : name;
                    String failure = failures.get(key);
                    if (failure == null) continue;
                    if (!first) message.append(';');
                    message.append(key).append('=').append(failure);
                    first = false;
                }
            }
            String detail = message.length() > 480
                    ? message.substring(0, 480) + "..." : message.toString();
            throw new IllegalStateException(detail);
        }
    }

    private static void requireDomain(String mode, Map<String, Boolean> installed,
            Map<String, String> bindingDetails, List<String> missing, String... hooks) {
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(mode)) return;
        for (String hook : hooks) {
            if (!Boolean.TRUE.equals(installed.get(hook))) {
                missing.add(hook);
            } else if (bindingDetails != null && requiresBindingDetail(hook)
                    && !bindingDetails.containsKey(hook)) {
                missing.add(hook + "(binding-contract)");
            }
        }
    }

    private static boolean requiresBindingDetail(String hook) {
        return !"deviceIdentity".equals(hook);
    }
}
