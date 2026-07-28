package com.warden.controlledsandbox.runtime.capability;

import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import java.util.List;
import java.util.Map;

public final class CapabilityProxyReadinessSelfTest {
    public static void main(String[] args) {
        List<VirtualPermissionSnapshot> permissions = List.of(
                new VirtualPermissionSnapshot("android.permission.CAMERA", "GRANTED", true),
                new VirtualPermissionSnapshot("android.permission.RECORD_AUDIO", "DENIED", false),
                new VirtualPermissionSnapshot("android.permission.ACCESS_FINE_LOCATION", "GRANTED", true));
        require(CapabilityProxyReadiness.missing(Map.of("camera", true, "location", true), permissions).isEmpty(),
                "installed granted capabilities accepted");
        require(CapabilityProxyReadiness.missing(Map.of("camera", true), permissions).equals(List.of("location")),
                "missing location proxy detected");
        boolean rejected = false;
        try { CapabilityProxyReadiness.require(Map.of(), permissions); }
        catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("camera") && expected.getMessage().contains("location");
        }
        require(rejected, "effective grant fails closed without proxy");
        System.out.println("PASS capability proxy readiness self-test");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
