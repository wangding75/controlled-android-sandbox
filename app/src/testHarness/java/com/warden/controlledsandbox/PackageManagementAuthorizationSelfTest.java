package com.warden.controlledsandbox;

public final class PackageManagementAuthorizationSelfTest {
    private PackageManagementAuthorizationSelfTest() { }

    public static void main(String[] args) {
        require(ManagementCallerPolicy.isHostApplication(2000, 100, 2000),
                "Host application UID should mint a management capability");
        require(!ManagementCallerPolicy.isHostApplication(2001, 100, 2000),
                "foreign UID must not mint a management capability");
        require(!ManagementCallerPolicy.isHostApplication(2000, 0, 2000),
                "invalid Binder PID must fail closed");

        require(ManagementCallerPolicy.isRuntimePeer(2000, 103, 2000, false, false),
                "Host application UID should mint a Runtime capability");
        require(ManagementCallerPolicy.isRuntimePeer(3000, 203, 2000, true, true),
                "signature-protected Companion UID should mint a Runtime capability");
        require(!ManagementCallerPolicy.isRuntimePeer(3000, 203, 2000, true, false),
                "a signed but non-Companion UID must be rejected");
        require(!ManagementCallerPolicy.isRuntimePeer(3000, 203, 2000, false, true),
                "Companion package UID without the signature permission must be rejected");
        require(!ManagementCallerPolicy.isRuntimePeer(3000, 0, 2000, true, true),
                "invalid Binder PID must fail closed");

        FakeIdentity host = new FakeIdentity(2000, 101, 2000, false, false);
        PackageCallerVerifier hostVerifier = new PackageCallerVerifier(host);
        hostVerifier.requireMainProcessCaller();
        hostVerifier.requireRuntimeBrokerCaller();

        FakeIdentity companion = new FakeIdentity(3000, 201, 2000, true, true);
        new PackageCallerVerifier(companion).requireRuntimeBrokerCaller();
        expectSecurity(() -> new PackageCallerVerifier(companion).requireMainProcessCaller(),
                "Companion must not mint management capability");

        expectSecurity(() -> new PackageCallerVerifier(
                        new FakeIdentity(3000, 201, 2000, true, false))
                        .requireRuntimeBrokerCaller(),
                "signed unknown package must fail closed");

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
        System.out.println("PASS package management UID authorization self-test");
    }

    private static final class FakeIdentity implements PackageCallerVerifier.CallerIdentitySource {
        private final int callingUid;
        private final int callingPid;
        private final int hostUid;
        private final boolean signaturePermissionGranted;
        private final boolean companionUid;

        FakeIdentity(int callingUid, int callingPid, int hostUid,
                boolean signaturePermissionGranted, boolean companionUid) {
            this.callingUid = callingUid;
            this.callingPid = callingPid;
            this.hostUid = hostUid;
            this.signaturePermissionGranted = signaturePermissionGranted;
            this.companionUid = companionUid;
        }

        @Override public int callingUid() { return callingUid; }
        @Override public int callingPid() { return callingPid; }
        @Override public int hostUid() { return hostUid; }
        @Override public boolean signaturePermissionGranted() { return signaturePermissionGranted; }
        @Override public boolean uidOwnsCompanionPackage(int uid) {
            return companionUid && uid == callingUid;
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
