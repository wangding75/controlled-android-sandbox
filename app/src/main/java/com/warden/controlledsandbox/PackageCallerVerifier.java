package com.warden.controlledsandbox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import com.warden.controlledsandbox.contract.RuntimePeerIdentity;
import java.util.Objects;

/** Authorizes package-service callers from stable Binder UID/package identity, not AMS process lists. */
final class PackageCallerVerifier {
    interface CallerIdentitySource {
        int callingUid();
        int callingPid();
        int hostUid();
        boolean signaturePermissionGranted();
        boolean uidOwnsCompanionPackage(int uid);
    }

    private final CallerIdentitySource identity;

    PackageCallerVerifier(Context context) {
        this(new AndroidCallerIdentitySource(Objects.requireNonNull(context, "context")
                .getApplicationContext()));
    }

    PackageCallerVerifier(CallerIdentitySource identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    void requireMainProcessCaller() {
        if (!ManagementCallerPolicy.isHostApplication(
                identity.callingUid(), identity.callingPid(), identity.hostUid())) {
            throw new SecurityException("PACKAGE_MANAGEMENT_CALLER_NOT_HOST_APPLICATION_UID");
        }
    }

    void requireRuntimeBrokerCaller() {
        int callerUid = identity.callingUid();
        if (!ManagementCallerPolicy.isRuntimePeer(callerUid, identity.callingPid(),
                identity.hostUid(), identity.signaturePermissionGranted(),
                identity.uidOwnsCompanionPackage(callerUid))) {
            throw new SecurityException("RUNTIME_PERMISSION_CALLER_NOT_TRUSTED_RUNTIME_UID");
        }
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
        @Override public boolean uidOwnsCompanionPackage(int uid) {
            PackageManager packages = context.getPackageManager();
            return packageUid(packages, RuntimePeerIdentity.COMPANION_RELEASE_PACKAGE) == uid
                    || packageUid(packages, RuntimePeerIdentity.COMPANION_DEBUG_PACKAGE) == uid;
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
