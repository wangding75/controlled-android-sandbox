package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.NativeGuestPolicyContract;

/**
 * Process-level admission boundary for Guest APKs that contain native code.
 *
 * <p>Guest-library PLT/GOT rebinding is only a compatibility and redirection mechanism. It cannot
 * intercept direct Linux syscalls or inline assembly. Native Guest code therefore requires an
 * explicit trust decision before it may be published or executed in the Host application UID.</p>
 */
final class NativeGuestExecutionPolicy {
    static final String ERROR_UNTRUSTED_NATIVE_GUEST_DENIED =
            NativeGuestPolicyContract.ERROR_UNTRUSTED_NATIVE_GUEST_DENIED;
    static final String MODE_NOT_APPLICABLE = NativeGuestPolicyContract.MODE_NOT_APPLICABLE;
    static final String MODE_BEST_EFFORT_COMPATIBILITY =
            NativeGuestPolicyContract.MODE_BEST_EFFORT_COMPATIBILITY;

    private NativeGuestExecutionPolicy() { }

    static String normalizeTrust(String value) {
        try {
            return NativeGuestPolicyContract.normalizeTrust(value);
        } catch (SecurityException error) {
            throw new IllegalArgumentException("Unsupported native Guest trust level: " + value,
                    error);
        }
    }

    static void requireInstallAllowed(boolean containsNativeCode, String trustLevel) {
        requireAllowed(containsNativeCode, trustLevel, "install");
    }

    static void requireRuntimeAllowed(SandboxRecord record) {
        if (record == null) throw new IllegalArgumentException("record is required");
        requireAllowed(record.containsNativeCode, record.nativeGuestTrust, "runtime");
    }

    static boolean isRuntimeAllowed(SandboxRecord record) {
        if (record == null || !record.containsNativeCode) return true;
        return NativeGuestPolicyContract.isExplicitlyTrusted(
                record.containsNativeCode, record.nativeGuestTrust);
    }

    static String executionMode(boolean containsNativeCode) {
        return NativeGuestPolicyContract.executionMode(containsNativeCode);
    }

    private static void requireAllowed(boolean containsNativeCode, String trustLevel, String phase) {
        if (!containsNativeCode) return;
        String normalized = normalizeTrust(trustLevel);
        if (!NativeGuestPolicyContract.isExplicitlyTrusted(containsNativeCode, normalized)) {
            throw new NativeGuestPolicyException(ERROR_UNTRUSTED_NATIVE_GUEST_DENIED,
                    ERROR_UNTRUSTED_NATIVE_GUEST_DENIED + ": native Guest " + phase
                            + " requires an explicit trust decision; PLT/GOT hooks are best-effort only");
        }
    }
}
