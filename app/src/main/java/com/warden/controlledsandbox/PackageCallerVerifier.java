package com.warden.controlledsandbox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import com.warden.controlledsandbox.contract.RuntimePeerIdentity;
import java.util.Objects;

/** Resolves stable Binder UID/PID and signed package identity for capability bootstrap. */
final class PackageCallerVerifier {
    static final String MANAGEMENT_ROLE = "HOST_MANAGEMENT";
    static final String HOST_RUNTIME_ROLE = "HOST_RUNTIME";
    static final String COMPANION_RUNTIME_ROLE_PREFIX = "COMPANION_RUNTIME:";

    interface CallerIdentitySource {
        int callingUid();
        int callingPid();
        int hostUid();
        boolean signaturePermissionGranted();
        String companionPackageForUid(int uid);
    }

    static final class VerifiedCaller {
        final int uid;
        final int pid;
        final String role;

        VerifiedCaller(int uid, int pid, String role) {
            this.uid = uid;
            this.pid = pid;
            this.role = Objects.requireNonNull(role, "role");
        }
    }

    private final CallerIdentitySource identity;

    PackageCallerVerifier(Context context) {
        this(new AndroidCallerIdentitySource(Objects.requireNonNull(context, "context")
                .getApplicationContext()));
    }

    PackageCallerVerifier(CallerIdentitySource identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    VerifiedCaller managementCaller() {
        int uid = identity.callingUid();
        int pid = identity.callingPid();
        if (!ManagementCallerPolicy.canBootstrapManagement(uid, pid, identity.hostUid())) {
            throw new SecurityException("PACKAGE_MANAGEMENT_CALLER_NOT_HOST_UID");
        }
        return new VerifiedCaller(uid, pid, MANAGEMENT_ROLE);
    }

    VerifiedCaller runtimeCaller() {
        int uid = identity.callingUid();
        int pid = identity.callingPid();
        String companion = uid == identity.hostUid() ? null : identity.companionPackageForUid(uid);
        if (!ManagementCallerPolicy.canBootstrapRuntime(uid, pid, identity.hostUid(),
                identity.signaturePermissionGranted(), companion)) {
            throw new SecurityException("RUNTIME_PERMISSION_CALLER_NOT_TRUSTED_UID");
        }
        String role = uid == identity.hostUid()
                ? HOST_RUNTIME_ROLE : COMPANION_RUNTIME_ROLE_PREFIX + companion;
        return new VerifiedCaller(uid, pid, role);
    }

    private static final class AndroidCallerIdentitySource implements CallerIdentitySource {
        private final Context context;

        AndroidCallerIdentitySource(Context context) { this.context = context; }

        @Override public int callingUid() { return Binder.getCallingUid(); }
        @Override public int callingPid() { return Binder.getCallingPid(); }
        @Override public int hostUid() { return context.getApplicationInfo().uid; }
        @Override public boolean signaturePermissionGranted() {
            return context.checkCallingPermission(RuntimePeerIdentity.SIGNATURE_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        @Override public String companionPackageForUid(int uid) {
            PackageManager packages = context.getPackageManager();
            if (packageUid(packages, RuntimePeerIdentity.COMPANION_RELEASE_PACKAGE) == uid) {
                return RuntimePeerIdentity.COMPANION_RELEASE_PACKAGE;
            }
            if (packageUid(packages, RuntimePeerIdentity.COMPANION_DEBUG_PACKAGE) == uid) {
                return RuntimePeerIdentity.COMPANION_DEBUG_PACKAGE;
            }
            return null;
        }

        private static int packageUid(PackageManager packages, String packageName) {
            try {
                return packages.getPackageUid(packageName, 0);
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                return -1;
            }
        }
    }
}
