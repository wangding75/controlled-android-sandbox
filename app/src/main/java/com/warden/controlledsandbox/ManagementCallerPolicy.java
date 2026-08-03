package com.warden.controlledsandbox;

/** Pure UID/PID eligibility policy used only to bootstrap unforgeable Binder capabilities. */
final class ManagementCallerPolicy {
    private ManagementCallerPolicy() { }

    static boolean canBootstrapManagement(int callingUid, int callingPid, int hostUid) {
        return callingPid > 0 && callingUid == hostUid;
    }

    static boolean canBootstrapRuntime(int callingUid, int callingPid, int hostUid,
            boolean signaturePermissionGranted, String companionPackage) {
        if (callingPid <= 0) return false;
        if (callingUid == hostUid) return true;
        return signaturePermissionGranted
                && companionPackage != null
                && !companionPackage.isBlank();
    }
}
