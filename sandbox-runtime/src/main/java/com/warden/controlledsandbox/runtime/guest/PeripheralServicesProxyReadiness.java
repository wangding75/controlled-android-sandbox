package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import java.util.Map;

/** Fail-closed readiness for external device and peripheral service proxies. */
final class PeripheralServicesProxyReadiness {
    private PeripheralServicesProxyReadiness() { }

    static void require(
            Map<String, Boolean> installed, VirtualPeripheralServicesProfileSnapshot profile) {
        if (profile == null) throw new IllegalStateException("VIRTUAL_PERIPHERAL_SERVICES_PROFILE_MISSING");
        requireNfc(installed, profile);
        require(installed, "usb", profile.usb().mode(), "VIRTUAL_USB_PROXY_REQUIRED");
        require(installed, "print", profile.printing().mode(), "VIRTUAL_PRINT_PROXY_REQUIRED");
        require(installed, "companionDevice", profile.companionDevice().mode(),
                "VIRTUAL_COMPANION_DEVICE_PROXY_REQUIRED");
        require(installed, "mediaProjection", profile.mediaProjection().mode(),
                "VIRTUAL_MEDIA_PROJECTION_PROXY_REQUIRED");
        require(installed, "camera", profile.camera().mode(), "VIRTUAL_CAMERA_PROFILE_PROXY_REQUIRED");
        if (!profile.oemSystemServices().serviceNames().isEmpty()) {
            require(installed, "oemSystemServices", profile.oemSystemServices().mode(),
                    "VIRTUAL_OEM_SYSTEM_SERVICES_PROXY_REQUIRED");
        }
    }

    static void requireNfc(
            Map<String, Boolean> installed, VirtualPeripheralServicesProfileSnapshot profile) {
        if (profile == null) throw new IllegalStateException("VIRTUAL_PERIPHERAL_SERVICES_PROFILE_MISSING");
        require(installed, "nfc", profile.nfc().mode(), "VIRTUAL_NFC_PROXY_REQUIRED");
    }

    private static void require(
            Map<String, Boolean> installed, String key, String mode, String error) {
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(mode)
                && !Boolean.TRUE.equals(installed.get(key))) {
            throw new IllegalStateException(error);
        }
    }
}
