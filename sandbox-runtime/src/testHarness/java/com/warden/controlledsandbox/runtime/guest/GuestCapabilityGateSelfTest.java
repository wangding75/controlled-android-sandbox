package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import java.util.List;

public final class GuestCapabilityGateSelfTest {
    private GuestCapabilityGateSelfTest() { }

    public static void main(String[] args) {
        GuestCapabilityGate denied = new GuestCapabilityGate(List.of(
                new VirtualPermissionSnapshot("android.permission.CAMERA", "GRANTED", false,
                        true, false, true, "android:camera", "PENDING"),
                new VirtualPermissionSnapshot("android.permission.ACCESS_FINE_LOCATION", "DENIED", false,
                        true, true, false, "android:fine_location", "DENIED")));
        expectSecurity(() -> denied.requireService("camera"),
                "camera handle must be blocked without effective grant");
        expectSecurity(() -> denied.requireService("location"),
                "location handle must be blocked without effective grant");
        denied.requireService("notification");

        denied.replace(List.of(
                new VirtualPermissionSnapshot("android.permission.CAMERA", "GRANTED", true,
                        true, true, false, "android:camera", "GRANTED"),
                new VirtualPermissionSnapshot("android.permission.ACCESS_COARSE_LOCATION", "GRANTED", true,
                        true, true, false, "android:coarse_location", "GRANTED")));
        denied.requireService("camera");
        denied.requireService("location");
        denied.replace(List.of(
                new VirtualPermissionSnapshot("android.permission.CAMERA", "DENIED", false,
                        true, true, false, "android:camera", "DENIED")));
        expectSecurity(() -> denied.requireService("camera"),
                "same-generation revocation must remove camera handle access");
        System.out.println("PASS Guest dangerous-capability service gate self-test");
    }

    private static void expectSecurity(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (SecurityException expected) { }
    }
}
