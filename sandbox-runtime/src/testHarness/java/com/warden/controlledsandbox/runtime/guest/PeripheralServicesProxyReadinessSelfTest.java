package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.*;
import java.util.List;
import java.util.Map;

/** M5-T15 fail-closed proxy-readiness tests for peripheral system services. */
public final class PeripheralServicesProxyReadinessSelfTest {
    public static void main(String[] args) {
        VirtualPeripheralServicesProfileSnapshot profile = profile("STATIC", true);
        PeripheralServicesProxyReadiness.require(Map.of(
                "nfc", true,
                "usb", true,
                "print", true,
                "companionDevice", true,
                "mediaProjection", true,
                "camera", true,
                "oemSystemServices", true), profile);
        requireFailure(() -> PeripheralServicesProxyReadiness.require(
                        Map.of("nfc", true), profile),
                "USB", "missing USB proxy blocks startup");
        requireFailure(() -> PeripheralServicesProxyReadiness.require(Map.of(
                        "nfc", true, "usb", true, "print", true,
                        "companionDevice", true, "mediaProjection", true, "camera", true),
                profile), "OEM", "configured OEM service proxy is required");
        PeripheralServicesProxyReadiness.require(Map.of(), profile("HOST", false));
        System.out.println("PASS M5-T15 peripheral-services proxy readiness self-test");
    }

    private static VirtualPeripheralServicesProfileSnapshot profile(String mode, boolean includeOem) {
        return new VirtualPeripheralServicesProfileSnapshot(1L, 0L,
                new VirtualNfcProfileSnapshot(mode, "OFF", false, false, false, 0, 0, List.of()),
                new VirtualUsbProfileSnapshot(mode, false, false, false, false, 0,
                        "none", List.of(), List.of()),
                new VirtualPrintProfileSnapshot(mode, false, false, 0, "", "", List.of()),
                new VirtualCompanionDeviceProfileSnapshot(mode, false, false, false,
                        false, 0, List.of(), List.of()),
                new VirtualMediaProjectionProfileSnapshot(mode, false, false, false,
                        true, 0, 1080, 1920, 420),
                new VirtualCameraProfileSnapshot(mode, false, false, false, 0,
                        List.of(), List.of(), List.of()),
                new VirtualOemSystemServicesProfileSnapshot(mode,
                        includeOem ? List.of("vendor.demo") : List.of(),
                        List.of("get"), List.of("set"), 0));
    }

    private static void requireFailure(Runnable action, String expected, String message) {
        boolean failed = false;
        try {
            action.run();
        } catch (IllegalStateException error) {
            failed = error.getMessage().contains(expected);
        }
        if (!failed) throw new AssertionError(message);
    }
}
