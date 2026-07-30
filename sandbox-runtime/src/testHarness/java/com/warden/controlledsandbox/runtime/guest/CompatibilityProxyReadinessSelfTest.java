package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualGoogleServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;
import java.util.List;
import java.util.Map;

public final class CompatibilityProxyReadinessSelfTest {
    public static void main(String[] args) {
        VirtualCompatibilityProfileSnapshot staticProfile = profile("STATIC", false, List.of());
        CompatibilityProxyReadiness.require(
                Map.of("webViewUpdate", true, "deviceIdentifiers", true),
                staticProfile,
                true);

        requireFailure(
                () -> CompatibilityProxyReadiness.require(
                        Map.of("webViewUpdate", true), staticProfile, true),
                "DEVICE_IDENTIFIERS",
                "missing identifiers hook blocks");
        requireFailure(
                () -> CompatibilityProxyReadiness.require(
                        Map.of("webViewUpdate", true, "deviceIdentifiers", true),
                        staticProfile,
                        false),
                "NATIVE_POLICY",
                "missing native detection policy blocks");
        requireFailure(
                () -> CompatibilityProxyReadiness.require(
                        Map.of("webViewUpdate", true, "deviceIdentifiers", true),
                        profile("STATIC", true, List.of()),
                        true),
                "GOOGLE_SERVICE_BROKER",
                "available GMS requires broker hook");
        requireFailure(
                () -> CompatibilityProxyReadiness.require(
                        Map.of("webViewUpdate", true, "deviceIdentifiers", true),
                        profile("STATIC", false, List.of("oem.ids")),
                        true),
                "OEM_IDENTIFIER",
                "configured OEM service requires hook");

        CompatibilityProxyReadiness.require(
                Map.of(), profile("HOST", false, List.of()), false);
        System.out.println("PASS M5-T12 compatibility proxy readiness self-test");
    }

    private static VirtualCompatibilityProfileSnapshot profile(
            String mode,
            boolean gmsAvailable,
            List<String> oemServices) {
        return new VirtualCompatibilityProfileSnapshot(
                1L,
                0L,
                new VirtualWebViewProfileSnapshot(
                        mode,
                        "com.android.webview",
                        "v",
                        "suffix",
                        "renderer",
                        true,
                        true,
                        false,
                        4),
                new VirtualGoogleServicesProfileSnapshot(
                        mode,
                        gmsAvailable,
                        "",
                        true,
                        "",
                        "",
                        "",
                        List.of(),
                        List.of()),
                new VirtualOemProfileSnapshot(
                        mode,
                        "",
                        "",
                        "",
                        List.of(),
                        List.of(),
                        oemServices,
                        List.of()),
                new VirtualDetectionPolicySnapshot(
                        mode,
                        true,
                        true,
                        true,
                        true,
                        true,
                        10,
                        List.of(),
                        List.of(),
                        List.of()));
    }

    private static void requireFailure(
            ThrowingAction action,
            String expectedMessagePart,
            String assertionMessage) {
        boolean failed = false;
        try {
            action.run();
        } catch (IllegalStateException error) {
            failed = error.getMessage().contains(expectedMessagePart);
        }
        require(failed, assertionMessage);
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
