package com.warden.controlledsandbox;

/** UID-based policy for package-service capability minting.
 *
 * Android processes that share an application UID are not an independent security boundary. The
 * root package service therefore authorizes the Host application UID and the explicitly installed,
 * signature-permission-protected Companion UID. Per-session PID guards still prevent a capability
 * minted by one Binder client from being reused by another process.
 */
final class ManagementCallerPolicy {
    private ManagementCallerPolicy() { }

    static boolean isHostApplication(int callingUid, int callingPid, int hostUid) {
        return callingPid > 0 && callingUid == hostUid;
    }

    static boolean isRuntimePeer(int callingUid, int callingPid, int hostUid,
            boolean signaturePermissionGranted, boolean companionUid) {
        if (callingPid <= 0) return false;
        if (callingUid == hostUid) return true;
        return signaturePermissionGranted && companionUid;
    }
}
