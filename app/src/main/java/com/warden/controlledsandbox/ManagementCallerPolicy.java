package com.warden.controlledsandbox;

/** Pure policy for package-service capability minting from stable UID and exact caller PID name. */
final class ManagementCallerPolicy {
    private ManagementCallerPolicy() { }

    static boolean isExpectedProcess(int callingUid, int callingPid, int expectedUid,
            String actualProcessName, String expectedProcessName) {
        return callingPid > 0
                && callingUid == expectedUid
                && expectedProcessName != null
                && !expectedProcessName.isBlank()
                && expectedProcessName.equals(actualProcessName);
    }

    static boolean isTrustedCompanionProcess(int callingUid, int callingPid,
            boolean signaturePermissionGranted, String companionPackage,
            String actualProcessName, String expectedProcessName) {
        return callingPid > 0
                && signaturePermissionGranted
                && companionPackage != null
                && !companionPackage.isBlank()
                && expectedProcessName != null
                && expectedProcessName.equals(actualProcessName);
    }
}
