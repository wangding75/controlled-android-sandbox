package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import java.util.Map;
import java.util.Set;

/** Ensures an already-wired Guest generation observes replacement policy snapshots. */
public final class DynamicAccessPolicySelfTest {
    private DynamicAccessPolicySelfTest() { }

    public static void main(String[] args) {
        VirtualPermissionPolicy permissions = new VirtualPermissionPolicy(
                Set.of("android.permission.CAMERA"),
                Map.of("android.permission.CAMERA", "DENIED"),
                Set.of());
        require(!permissions.isGranted("android.permission.CAMERA"), "initial permission must be denied");
        require("DENIED".equals(permissions.decision("android.permission.CAMERA")),
                "initial decision must be denied");

        permissions.replace(Set.of("android.permission.CAMERA"),
                Map.of("android.permission.CAMERA", "GRANTED"),
                Set.of("android.permission.CAMERA"));
        require(permissions.isGranted("android.permission.CAMERA"),
                "same-generation permission replacement must become visible");
        require("GRANTED".equals(permissions.decision("android.permission.CAMERA")),
                "same-generation decision replacement must become visible");

        permissions.replace(Set.of("android.permission.CAMERA"),
                Map.of("android.permission.CAMERA", "DENIED"), Set.of());
        require(!permissions.isGranted("android.permission.CAMERA"),
                "same-generation revocation must become visible");

        SandboxAppOpsPolicy appOps = new SandboxAppOpsPolicy(Map.of("android:camera", "IGNORED"));
        require(appOps.modeCode("android:camera") == 1, "initial AppOps mode must be ignored");
        appOps.replace(Map.of("android:camera", "ALLOWED"));
        require(appOps.modeCode("android:camera") == 0,
                "same-generation AppOps replacement must become visible");
        appOps.replace(Map.of("android:camera", "ERRORED"));
        require(appOps.modeCode("android:camera") == 2,
                "same-generation AppOps revocation must become visible");

        System.out.println("PASS dynamic permission and AppOps policy replacement self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
