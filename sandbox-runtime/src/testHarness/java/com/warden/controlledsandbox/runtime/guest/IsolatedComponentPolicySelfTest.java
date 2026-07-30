package com.warden.controlledsandbox.runtime.guest;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import java.util.List;
import java.util.Set;

public final class IsolatedComponentPolicySelfTest {
    private IsolatedComponentPolicySelfTest() { }

    public static void main(String[] args) {
        VirtualPackageMetadata metadata = new VirtualPackageMetadata(
                "com.example", "", new ApplicationInfo(), List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.SERVICE,
                        "com.example.NormalService", "com.example", false, true, false, Set.of(), ""),
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.SERVICE,
                        "com.example.IsolatedService", "com.example:isolated", false, true, true, Set.of(), "")));
        IsolatedComponentPolicy.requireSupported(metadata, "com.example.NormalService");
        IsolatedComponentPolicy.requireSupported(metadata, "");
        expectFailure(() -> IsolatedComponentPolicy.requireSupported(metadata, "com.example.IsolatedService"));
        IsolatedComponentPolicy.requireSupported(metadata, "com.example.IsolatedService", true);
        IsolatedComponentPolicy.requireSupported(metadata, ".IsolatedService", true);
        check(metadata.isIsolatedComponent(".IsolatedService"), "relative isolated class lookup failed");
        System.out.println("PASS isolatedProcess ordinary fail-closed and dedicated-transport policy self-test");
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); }
        catch (UnsupportedOperationException expected) {
            if (!String.valueOf(expected.getMessage()).startsWith("ISOLATED_PROCESS_UNAVAILABLE")) {
                throw new AssertionError("wrong isolatedProcess failure", expected);
            }
            return;
        }
        throw new AssertionError("isolated component must fail closed on ordinary transport");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
