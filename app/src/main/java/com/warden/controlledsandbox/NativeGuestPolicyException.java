package com.warden.controlledsandbox;

/** Stable fail-closed error raised when native Guest execution is not explicitly trusted. */
final class NativeGuestPolicyException extends SecurityException {
    private static final long serialVersionUID = 1L;
    private final String code;

    NativeGuestPolicyException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() { return code; }
}
