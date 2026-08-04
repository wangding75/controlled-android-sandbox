package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import java.util.List;

public final class GuestClassLoaderSelfTest {
    public static void main(String[] args) {
        require(GuestClassLoader.isParentFirst("java.lang.String"), "Java parent first");
        require(GuestClassLoader.isParentFirst("android.app.Activity"), "Android parent first");
        require(GuestClassLoader.isParentFirst("com.warden.controlledsandbox.contract.IRuntimeBroker"),
                "sandbox contract parent first");
        require(GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment"),
                "runtime implementation denied");
        require(GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.framework.core.FrameworkHooks"),
                "framework implementation denied");
        require(GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.nativebridge.NativePolicy"),
                "native management implementation denied");
        require(!GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.contract.IRuntimeBroker"),
                "contract remains available");
        require(GuestClassLoader.isPrivilegedContract(
                "com.warden.controlledsandbox.contract.IPackageService"),
                "privileged Package Service contract denied");
        require(!GuestClassLoader.isPrivilegedContract(
                "com.warden.controlledsandbox.contract.IRuntimeBroker"),
                "Guest-safe runtime contract remains available");
        require(!GuestClassLoader.isParentFirst("com.example.guest.MainActivity"), "Guest child first");
        require(!GuestClassLoader.isParentFirst("org.example.library.Client"), "Guest libraries child first");
        GuestClassLoader loader = new GuestClassLoader("", "", null, GuestClassLoaderSelfTest.class.getClassLoader());
        loader.configureDetection(new VirtualDetectionPolicySnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, true, true, true, true, true, 2,
                List.of(), List.of("com.example.hidden", "org.example.internal"), List.of()));
        boolean hidden = false;
        try { loader.loadClass("com.example.hidden.DetectionBridge"); }
        catch (ClassNotFoundException expected) { hidden = true; }
        require(hidden && loader.suspiciousQueryCount() == 1, "policy-hidden class query");
        hidden = false;
        try { loader.loadClass("org.example.internal.RuntimeBridge"); }
        catch (ClassNotFoundException expected) { hidden = true; }
        require(hidden && loader.suspiciousQueryCount() == 2, "second policy-hidden class query");
        loader.configureDetection(new VirtualDetectionPolicySnapshot(
                VirtualLocationProfileSnapshot.MODE_HOST, false, false, false, false, false, 0,
                List.of(), List.of(), List.of()));
        require(loader.suspiciousQueryCount() == 0, "HOST mode resets detection ledger");
        System.out.println("PASS Guest class-loader host-boundary and detection policy self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
