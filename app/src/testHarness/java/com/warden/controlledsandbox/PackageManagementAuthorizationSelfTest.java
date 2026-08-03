package com.warden.controlledsandbox;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.RuntimePeerIdentity;

/** Direct regression for generation-bound, PID-owned and death-linked role capabilities. */
public final class PackageManagementAuthorizationSelfTest {
    private PackageManagementAuthorizationSelfTest() { }

    public static void main(String[] args) {
        String companion = RuntimePeerIdentity.COMPANION_RELEASE_PACKAGE;
        MutableIdentity identity = new MutableIdentity(2000, 100, 2000, false, null);
        PackageCallerVerifier verifier = new PackageCallerVerifier(identity);
        PackageAuthorityCapabilityRegistry registry =
                new PackageAuthorityCapabilityRegistry(verifier);
        try {
            require(ManagementCallerPolicy.canBootstrapManagement(2000, 100, 2000),
                    "Host UID should be eligible to bootstrap management capability");
            require(!ManagementCallerPolicy.canBootstrapManagement(2001, 100, 2000),
                    "foreign UID must not bootstrap management capability");
            require(!ManagementCallerPolicy.canBootstrapManagement(2000, 0, 2000),
                    "invalid Binder PID must fail closed");

            TestBinder management = new TestBinder();
            registry.registerManagement(management, 10L);
            registry.requireManagement(management, 10L);
            registry.registerManagement(management, 10L); // idempotent reconnect

            identity.pid = 101;
            expectSecurity(() -> registry.requireManagement(management, 10L),
                    "different same-UID process reused management capability");
            expectSecurity(() -> registry.registerManagement(new TestBinder(), 11L),
                    "second same-UID process replaced live management role");

            identity.pid = 100;
            expectSecurity(() -> registry.requireManagement(management, 11L),
                    "management generation mismatch was accepted");
            management.die();
            expectSecurity(() -> registry.requireManagement(management, 10L),
                    "dead management capability remained active");

            identity.pid = 102;
            TestBinder replacement = new TestBinder();
            registry.registerManagement(replacement, 11L);
            registry.requireManagement(replacement, 11L);
            expectSecurity(() -> registry.registerManagement(new TestBinder(), 11L),
                    "management generation was not required to advance");

            identity.uid = 2000;
            identity.pid = 200;
            identity.permission = false;
            identity.companionPackage = null;
            TestBinder hostRuntime = new TestBinder();
            registry.registerRuntime(hostRuntime, 20L);
            registry.requireRuntime(hostRuntime, 20L);
            identity.pid = 201;
            expectSecurity(() -> registry.requireRuntime(hostRuntime, 20L),
                    "same-UID Guest process reused Runtime capability");
            expectSecurity(() -> registry.registerRuntime(new TestBinder(), 21L),
                    "same-UID Guest process replaced live Runtime role");

            identity.uid = 3000;
            identity.pid = 300;
            identity.permission = true;
            identity.companionPackage = companion;
            TestBinder companionRuntime = new TestBinder();
            registry.registerRuntime(companionRuntime, 30L);
            registry.requireRuntime(companionRuntime, 30L);

            identity.companionPackage = null;
            expectSecurity(() -> registry.requireRuntime(companionRuntime, 30L),
                    "package identity lookup failure did not fail closed");
            identity.companionPackage = companion;
            identity.permission = false;
            expectSecurity(() -> registry.requireRuntime(companionRuntime, 30L),
                    "Companion without signature permission remained authorized");

            identity.uid = 4000;
            identity.pid = 400;
            identity.permission = false;
            identity.companionPackage = null;
            expectSecurity(() -> registry.registerRuntime(new TestBinder(), 40L),
                    "foreign unsigned UID bootstrapped Runtime capability");

            ManagementSessionGuard managementGuard = new ManagementSessionGuard(2000, 100);
            managementGuard.requireOwner(2000, 100);
            expectSecurity(() -> managementGuard.requireOwner(2000, 101),
                    "a different process reused the management session");
            managementGuard.close();
            expectSecurity(() -> managementGuard.requireOwner(2000, 100),
                    "closed management session remained callable");

            RuntimePermissionSessionGuard runtimeGuard =
                    new RuntimePermissionSessionGuard(2000, 200);
            runtimeGuard.requireOwner(2000, 200);
            expectSecurity(() -> runtimeGuard.requireOwner(2000, 201),
                    "a different process reused the Runtime session");
            runtimeGuard.close();
            expectSecurity(() -> runtimeGuard.requireOwner(2000, 200),
                    "closed Runtime session remained callable");
        } finally {
            registry.close();
        }
        System.out.println("PASS package authority Binder capability authorization self-test");
    }

    private static final class MutableIdentity
            implements PackageCallerVerifier.CallerIdentitySource {
        int uid;
        int pid;
        final int hostUid;
        boolean permission;
        String companionPackage;

        MutableIdentity(int uid, int pid, int hostUid, boolean permission,
                String companionPackage) {
            this.uid = uid;
            this.pid = pid;
            this.hostUid = hostUid;
            this.permission = permission;
            this.companionPackage = companionPackage;
        }

        @Override public int callingUid() { return uid; }
        @Override public int callingPid() { return pid; }
        @Override public int hostUid() { return hostUid; }
        @Override public boolean signaturePermissionGranted() { return permission; }
        @Override public String companionPackageForUid(int candidateUid) {
            return candidateUid == uid ? companionPackage : null;
        }
    }

    private static final class TestBinder implements IBinder {
        private DeathRecipient recipient;
        private boolean alive = true;

        @Override public boolean isBinderAlive() { return alive; }
        @Override public void linkToDeath(DeathRecipient value, int flags) {
            if (!alive) throw new IllegalStateException("dead");
            recipient = value;
        }
        @Override public boolean unlinkToDeath(DeathRecipient value, int flags) {
            if (recipient != value) return false;
            recipient = null;
            return true;
        }

        void die() {
            alive = false;
            DeathRecipient current = recipient;
            if (current != null) current.binderDied();
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
