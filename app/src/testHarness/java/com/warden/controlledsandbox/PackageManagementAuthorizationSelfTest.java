package com.warden.controlledsandbox;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract;
import com.warden.controlledsandbox.contract.RuntimePeerIdentity;

/** Direct regression for private-bootstrap, server-epoch and death-linked role capabilities. */
public final class PackageManagementAuthorizationSelfTest {
    private PackageManagementAuthorizationSelfTest() { }

    public static void main(String[] args) {
        String companion = RuntimePeerIdentity.COMPANION_RELEASE_PACKAGE;
        MutableIdentity identity = new MutableIdentity(2000, 100, 2000, false, null);
        PackageCallerVerifier verifier = new PackageCallerVerifier(identity);
        PackageAuthorityCapabilityRegistry registry =
                new PackageAuthorityCapabilityRegistry(verifier);
        try {
            TestBinder management = new TestBinder();
            registry.installManagement(management, 2000, 100);
            identity.pid = 101;
            expectSecurity(() -> registry.requireManagement(management,
                            PackageAuthorityCapabilityContract.SERVER_MANAGED_EPOCH),
                    "first same-UID process claimed management capability");
            identity.pid = 100;
            registry.requireManagement(management,
                    PackageAuthorityCapabilityContract.SERVER_MANAGED_EPOCH);

            expectSecurity(() -> registry.requireManagement(new TestBinder(), 0L),
                    "caller-supplied management token was accepted");
            expectSecurity(() -> registry.requireManagement(management, Long.MAX_VALUE),
                    "caller-controlled management generation was accepted");

            identity.pid = 101;
            expectSecurity(() -> registry.requireManagement(management, 0L),
                    "different same-UID process reused management capability");

            identity.pid = 100;
            management.die();
            expectSecurity(() -> registry.requireManagement(management, 0L),
                    "dead management capability remained active");

            identity.pid = 102;
            TestBinder replacement = new TestBinder();
            registry.installManagement(replacement, 2000, 102);
            registry.requireManagement(replacement, 0L);

            identity.uid = 2000;
            identity.pid = 200;
            TestBinder hostRuntime = new TestBinder();
            registry.installRuntime(hostRuntime, 2000, 200);
            registry.requireRuntime(hostRuntime, 0L);
            identity.pid = 201;
            expectSecurity(() -> registry.requireRuntime(hostRuntime, 0L),
                    "same-UID Guest process reused Runtime capability");
            expectSecurity(() -> registry.requireRuntime(new TestBinder(), 0L),
                    "same-UID Guest process substituted Runtime capability");

            identity.uid = 3000;
            identity.pid = 300;
            identity.permission = true;
            identity.companionPackage = companion;
            expectSecurity(() -> registry.requireRuntime(hostRuntime, 0L),
                    "Companion reused Host Runtime capability");
            TestBinder companionRuntime = new TestBinder();
            registry.installCompanionRuntime(companion, companionRuntime, 3000, 300);
            registry.requireRuntime(companionRuntime, 0L);
            identity.pid = 301;
            expectSecurity(() -> registry.requireRuntime(companionRuntime, 0L),
                    "different Companion process reused Runtime capability");
            identity.pid = 300;
            companionRuntime.die();
            expectSecurity(() -> registry.requireRuntime(companionRuntime, 0L),
                    "dead Companion Runtime capability remained active");

            ManagementSessionGuard managementGuard = new ManagementSessionGuard(2000, 100);
            managementGuard.requireOwner(2000, 100);
            expectSecurity(() -> managementGuard.requireOwner(2000, 101),
                    "a different process reused the management session");
            managementGuard.close();
            expectSecurity(() -> managementGuard.requireOwner(2000, 100),
                    "closed management session remained callable");
        } finally {
            registry.close();
        }
        System.out.println("PASS private Package Authority bootstrap self-test");
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

    private static void expectSecurity(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (SecurityException expected) {
            // expected
        }
    }
}
