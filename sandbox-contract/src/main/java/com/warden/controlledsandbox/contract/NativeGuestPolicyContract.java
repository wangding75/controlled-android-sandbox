package com.warden.controlledsandbox.contract;

/** Shared contract validation for the explicit Native Guest admission decision. */
public final class NativeGuestPolicyContract {
    public static final String ERROR_UNTRUSTED_NATIVE_GUEST_DENIED =
            "UNTRUSTED_NATIVE_GUEST_DENIED";
    public static final String MODE_NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String MODE_BEST_EFFORT_COMPATIBILITY =
            "BEST_EFFORT_COMPATIBILITY";

    private NativeGuestPolicyContract() { }

    public static String normalizeTrust(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            normalized = InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED;
        }
        if (!InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED.equals(normalized)
                && !InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED.equals(
                normalized)) {
            throw new SecurityException("INVALID_NATIVE_GUEST_TRUST");
        }
        return normalized;
    }

    public static String executionMode(boolean containsNativeCode) {
        return containsNativeCode ? MODE_BEST_EFFORT_COMPATIBILITY : MODE_NOT_APPLICABLE;
    }

    public static boolean isExplicitlyTrusted(boolean containsNativeCode, String trust) {
        return !containsNativeCode
                || InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED.equals(
                normalizeTrust(trust));
    }

    public static void validateMetadata(boolean containsNativeCode, String executionMode,
                                        String nativeLibraryDir) {
        if (!containsNativeCode && nativeLibraryDir != null && !nativeLibraryDir.trim().isEmpty()) {
            throw new SecurityException("NATIVE_GUEST_METADATA_MISMATCH");
        }
        if (!executionMode(containsNativeCode).equals(executionMode)) {
            throw new SecurityException("NATIVE_GUEST_EXECUTION_MODE_MISMATCH");
        }
    }

    public static void requireAllowed(boolean containsNativeCode, String trust,
                                      String executionMode, String nativeLibraryDir) {
        validateMetadata(containsNativeCode, executionMode, nativeLibraryDir);
        if (!isExplicitlyTrusted(containsNativeCode, trust)) {
            throw new SecurityException(ERROR_UNTRUSTED_NATIVE_GUEST_DENIED);
        }
    }
}
