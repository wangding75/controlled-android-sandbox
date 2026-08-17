package com.warden.controlledsandbox.contract;

/**
 * Formal Native execution profile. Distinct from
 * {@link NativeGuestPolicyContract#executionMode(boolean)} which only labels
 * PLT/GOT compatibility applicability.
 */
public final class NativeExecutionProfile {
    public static final String TRUSTED_COMPAT = "TRUSTED_COMPAT";
    public static final String ISOLATED_HOSTILE = "ISOLATED_HOSTILE";

    private NativeExecutionProfile() { }

    public static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) return TRUSTED_COMPAT;
        if (!TRUSTED_COMPAT.equals(normalized) && !ISOLATED_HOSTILE.equals(normalized)) {
            throw new SecurityException("INVALID_NATIVE_EXECUTION_PROFILE");
        }
        return normalized;
    }

    public static boolean isHostile(String value) {
        return ISOLATED_HOSTILE.equals(normalize(value));
    }
}
