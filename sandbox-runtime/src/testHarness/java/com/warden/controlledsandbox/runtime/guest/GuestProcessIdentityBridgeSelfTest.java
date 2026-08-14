package com.warden.controlledsandbox.runtime.guest;

import android.os.Process;

public final class GuestProcessIdentityBridgeSelfTest {
    public static void main(String[] args) {
        Process.setArgV0("com.warden.host:guest2");
        require("com.warden.host:guest2".equals(GuestProcessIdentityBridge.readOsProcessName()),
                "reads current OS process name");
        require(GuestProcessIdentityBridge.publishOsProcessName("com.example.guest"),
                "publishes Process.setArgV0");
        require("com.example.guest".equals(Process.myProcessName()),
                "setArgV0 is visible through Process.myProcessName");
        require("com.example.guest".equals(GuestProcessIdentityBridge.readOsProcessName()),
                "published name is readable");
        require(!GuestProcessIdentityBridge.publishOsProcessName(" "),
                "blank process name is rejected");
        System.out.println("PASS guest process identity argv0 publisher self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
