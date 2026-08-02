package com.warden.controlledsandbox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import com.warden.controlledsandbox.contract.RuntimePeerIdentity;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Authorizes package-service callers from Binder UID and the exact caller PID identity. */
final class PackageCallerVerifier {
    private static final int MAX_PROCESS_NAME_BYTES = 512;

    interface CallerIdentitySource {
        int callingUid();
        int callingPid();
        int hostUid();
        String hostPackage();
        boolean signaturePermissionGranted();
        String companionPackageForUid(int uid);
        String processName(int pid);
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
        int uid = identity.callingUid();
        int pid = identity.callingPid();
        String expected = identity.hostPackage();
        if (!ManagementCallerPolicy.isExpectedProcess(
                uid, pid, identity.hostUid(), identity.processName(pid), expected)) {
            throw new SecurityException("PACKAGE_MANAGEMENT_CALLER_NOT_HOST_MAIN_PROCESS");
        }
    }

    void requireRuntimeBrokerCaller() {
        int uid = identity.callingUid();
        int pid = identity.callingPid();
        String actual = identity.processName(pid);
        if (uid == identity.hostUid()) {
            String expected = identity.hostPackage() + ":sandbox_server";
            if (ManagementCallerPolicy.isExpectedProcess(
                    uid, pid, identity.hostUid(), actual, expected)) return;
            throw new SecurityException("RUNTIME_PERMISSION_CALLER_NOT_HOST_BROKER_PROCESS");
        }
        String companion = identity.companionPackageForUid(uid);
        String expected = companion == null ? ""
                : RuntimePeerIdentity.companionBrokerProcess(companion);
        if (!ManagementCallerPolicy.isTrustedCompanionProcess(uid, pid,
                identity.signaturePermissionGranted(), companion, actual, expected)) {
            throw new SecurityException("RUNTIME_PERMISSION_CALLER_NOT_COMPANION_BROKER_PROCESS");
        }
    }

    private static final class AndroidCallerIdentitySource implements CallerIdentitySource {
        private final Context context;

        AndroidCallerIdentitySource(Context context) { this.context = context; }

        @Override public int callingUid() { return Binder.getCallingUid(); }
        @Override public int callingPid() { return Binder.getCallingPid(); }
        @Override public int hostUid() { return context.getApplicationInfo().uid; }
        @Override public String hostPackage() { return context.getPackageName(); }
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
        @Override public String processName(int pid) {
            if (pid <= 0) return "";
            byte[] buffer = new byte[MAX_PROCESS_NAME_BYTES];
            try (FileInputStream input = new FileInputStream("/proc/" + pid + "/cmdline")) {
                int length = input.read(buffer);
                if (length <= 0) return "";
                int end = 0;
                while (end < length && buffer[end] != 0) end++;
                return new String(buffer, 0, end, StandardCharsets.UTF_8).trim();
            } catch (Exception unavailable) {
                return "";
            }
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
