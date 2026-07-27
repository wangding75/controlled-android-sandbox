package com.warden.controlledsandbox;

import java.util.List;

public final class PackageManagementAuthorizationSelfTest {
    private PackageManagementAuthorizationSelfTest() { }

    public static void main(String[] args) {
        String host = "com.warden.controlledsandbox";
        List<ManagementCallerPolicy.ProcessIdentity> processes = List.of(
                new ManagementCallerPolicy.ProcessIdentity(100, 2000, host),
                new ManagementCallerPolicy.ProcessIdentity(101, 2000, host + ":guest0"),
                new ManagementCallerPolicy.ProcessIdentity(102, 2000, host + ":sandbox_package"),
                new ManagementCallerPolicy.ProcessIdentity(103, 2000, host + ":sandbox_server"));
        require(ManagementCallerPolicy.isAuthorized(2000, 100, 2000, host, processes),
                "main process should be authorized");
        require(!ManagementCallerPolicy.isAuthorized(2000, 101, 2000, host, processes),
                "guest process must be rejected");
        require(!ManagementCallerPolicy.isAuthorized(2000, 102, 2000, host, processes),
                "package service process must not mint a management session");
        require(!ManagementCallerPolicy.isAuthorized(2001, 100, 2000, host, processes),
                "foreign uid must be rejected");
        require(!ManagementCallerPolicy.isAuthorized(2000, 999, 2000, host, processes),
                "unknown pid must be rejected");
        require(ManagementCallerPolicy.isAuthorized(2000, 103, 2000,
                        host + ":sandbox_server", processes),
                "runtime broker process should mint only the runtime permission capability");
        require(!ManagementCallerPolicy.isAuthorized(2000, 101, 2000,
                        host + ":sandbox_server", processes),
                "Guest process must not mint the runtime permission capability");

        ManagementSessionGuard guard = new ManagementSessionGuard(2000, 100);
        guard.requireOwner(2000, 100);
        expectSecurity(() -> guard.requireOwner(2000, 101),
                "a guest process must not reuse the main-process capability");
        expectSecurity(() -> guard.requireOwner(2001, 100),
                "a foreign uid must not reuse the capability");
        guard.close();
        expectSecurity(() -> guard.requireOwner(2000, 100),
                "closed capability must remain closed");

        RuntimePermissionSessionGuard runtimeGuard = new RuntimePermissionSessionGuard(2000, 103);
        runtimeGuard.requireOwner(2000, 103);
        expectSecurity(() -> runtimeGuard.requireOwner(2000, 101),
                "Guest process must not reuse the Runtime Broker capability");
        runtimeGuard.close();
        expectSecurity(() -> runtimeGuard.requireOwner(2000, 103),
                "closed Runtime Broker capability must remain closed");
        System.out.println("PASS package management authorization self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectSecurity(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (SecurityException expected) {
            // expected
        }
    }
}
