package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.*;
import java.util.List;
import java.util.Map;

/** M5-T17 fail-closed proxy readiness tests. */
public final class PrivilegedServicesProxyReadinessSelfTest {
    public static void main(String[] args) {
        VirtualPrivilegedServicesProfileSnapshot profile = profile("STATIC");
        PrivilegedServicesProxyReadiness.require(Map.of(
                "search", true, "storageStats", true, "graphicsStats", true,
                "contextHub", true, "persistentDataBlock", true, "systemUpdate", true), profile);
        requireFailure(() -> PrivilegedServicesProxyReadiness.require(
                        Map.of("search", true), profile),
                "STORAGE", "missing StorageStats proxy blocks startup");
        requireFailure(() -> PrivilegedServicesProxyReadiness.require(Map.of(
                        "search", true, "storageStats", true, "graphicsStats", true,
                        "contextHub", true, "persistentDataBlock", true), profile),
                "SYSTEM_UPDATE", "missing SystemUpdate proxy blocks startup");
        PrivilegedServicesProxyReadiness.require(Map.of(), profile("HOST"));
        System.out.println("PASS M5-T17 privileged-services proxy readiness self-test");
    }

    private static VirtualPrivilegedServicesProfileSnapshot profile(String mode) {
        return new VirtualPrivilegedServicesProfileSnapshot(1L, 0L,
                new VirtualSearchProfileSnapshot(mode, false, false, "", "", List.of(), List.of(), 0),
                new VirtualStorageStatsProfileSnapshot(mode, 1L, 1L, 0L, 0L, 0L, 0L, 0L, false, false),
                new VirtualGraphicsStatsProfileSnapshot(mode, false, false, 0, 0L, 0L, 0L),
                new VirtualContextHubProfileSnapshot(mode, false, false, false, false, 0, List.of()),
                new VirtualPersistentDataBlockProfileSnapshot(mode, false, false, false,
                        0, new byte[0], false, 0, true),
                new VirtualSystemUpdateProfileSnapshot(mode, true, false,
                        "UNKNOWN", "", "", "", 0, 0L));
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
