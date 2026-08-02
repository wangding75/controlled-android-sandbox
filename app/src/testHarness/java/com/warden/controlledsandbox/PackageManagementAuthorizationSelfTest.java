package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.RuntimePeerIdentity;

public final class PackageManagementAuthorizationSelfTest {
    private PackageManagementAuthorizationSelfTest() { }

    public static void main(String[] args) {
        String host = RuntimePeerIdentity.HOST_RELEASE_PACKAGE;
        require(ManagementCallerPolicy.isExpectedProcess(2000, 100, 2000, host, host),
                "Host main process should be authorized");
        require(!ManagementCallerPolicy.isExpectedProcess(
                        2000, 101, 2000, host + ":guest0", host),
                "same-UID Guest process must not mint management capability");
        require(!ManagementCallerPolicy.isExpectedProcess(2001, 100, 2000, host, host),
                "foreign UID must be rejected");
        require(!ManagementCallerPolicy.isExpectedProcess(2000, 0, 2000, host, host),
                "invalid Binder PID must fail closed");
        require(!ManagementCallerPolicy.isExpectedProcess(2000, 100, 2000, "", host),
                "unavailable caller process identity must fail closed");

        String companion = RuntimePeerIdentity.COMPANION_RELEASE_PACKAGE;
        String companionBroker = RuntimePeerIdentity.companionBrokerProcess(companion);
        require(ManagementCallerPolicy.isTrustedCompanionProcess(
                        3000, 203, true, companion, companionBroker, companionBroker),
                "signature-protected Companion broker should be authorized");
        require(!ManagementCallerPolicy.isTrustedCompanionProcess(
                        3000, 203, true, companion, companion + ":native32", companionBroker),
                "other Companion process must be rejected");
        require(!ManagementCallerPolicy.isTrustedCompanionProcess(
                        3000, 203, false, companion, companionBroker, companionBroker),
                "Companion without signature permission must be rejected");

        FakeIdentity main = new FakeIdentity(2000, 100, 2000, host, false, null, host);
        new PackageCallerVerifier(main).requireMainProcessCaller();
        expectSecurity(() -> new PackageCallerVerifier(main).requireRuntimeBrokerCaller(),
                "Host main process must not mint Runtime capability");

        FakeIdentity guest = new FakeIdentity(
                2000, 101, 2000, host, false, null, host + ":guest0");
        expectSecurity(() -> new PackageCallerVerifier(guest).requireMainProcessCaller(),
                "same-UID Guest process must not mint management capability");

        FakeIdentity runtime = new FakeIdentity(
                2000, 103, 2000, host, false, null, host + ":sandbox_server");
        new PackageCallerVerifier(runtime).requireRuntimeBrokerCaller();

        FakeIdentity companionIdentity = new FakeIdentity(
                3000, 201, 2000, host, true, companion, companionBroker);
        new PackageCallerVerifier(companionIdentity).requireRuntimeBrokerCaller();
        expectSecurity(() -> new PackageCallerVerifier(companionIdentity).requireMainProcessCaller(),
                "Companion must not mint management capability");

        FakeIdentity hidden = new FakeIdentity(3000, 201, 2000, host, true, null, companionBroker);
        expectSecurity(() -> new PackageCallerVerifier(hidden).requireRuntimeBrokerCaller(),
                "package identity lookup failure must fail closed");

        ManagementSessionGuard guard = new ManagementSessionGuard(2000, 100);
        guard.requireOwner(2000, 100);
        expectSecurity(() -> guard.requireOwner(2000, 101),
                "a different process must not reuse the management capability");
        guard.close();
        expectSecurity(() -> guard.requireOwner(2000, 100),
                "closed capability must remain closed");

        RuntimePermissionSessionGuard runtimeGuard = new RuntimePermissionSessionGuard(2000, 103);
        runtimeGuard.requireOwner(2000, 103);
        expectSecurity(() -> runtimeGuard.requireOwner(2000, 101),
                "a different process must not reuse the Runtime capability");
        runtimeGuard.close();
        expectSecurity(() -> runtimeGuard.requireOwner(2000, 103),
                "closed Runtime capability must remain closed");
        System.out.println("PASS package management exact caller-process authorization self-test");
    }

    private static final class FakeIdentity implements PackageCallerVerifier.CallerIdentitySource {
        private final int callingUid;
        private final int callingPid;
        private final int hostUid;
        private final String hostPackage;
        private final boolean permission;
        private final String companionPackage;
        private final String processName;

        FakeIdentity(int callingUid, int callingPid, int hostUid, String hostPackage,
                boolean permission, String companionPackage, String processName) {
            this.callingUid = callingUid;
            this.callingPid = callingPid;
            this.hostUid = hostUid;
            this.hostPackage = hostPackage;
            this.permission = permission;
            this.companionPackage = companionPackage;
            this.processName = processName;
        }

        @Override public int callingUid() { return callingUid; }
        @Override public int callingPid() { return callingPid; }
        @Override public int hostUid() { return hostUid; }
        @Override public String hostPackage() { return hostPackage; }
        @Override public boolean signaturePermissionGranted() { return permission; }
        @Override public String companionPackageForUid(int uid) {
            return uid == callingUid ? companionPackage : null;
        }
        @Override public String processName(int pid) {
            return pid == callingPid ? processName : "";
        }
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
