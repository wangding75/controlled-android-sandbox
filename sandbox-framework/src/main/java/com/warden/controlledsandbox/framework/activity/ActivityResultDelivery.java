package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

/** Immutable result envelope waiting for delivery to a caller Activity. */
public record ActivityResultDelivery(
        String callerActivityToken,
        String calleeActivityToken,
        String resultWho,
        String registryKey,
        int requestCode,
        int resultCode,
        String intentSenderToken,
        String dataToken,
        ResultIntentSnapshot resultIntent) {

    public ActivityResultDelivery {
        callerActivityToken = requireText(callerActivityToken, "callerActivityToken");
        calleeActivityToken = requireText(calleeActivityToken, "calleeActivityToken");
        resultWho = optional(resultWho, 256);
        registryKey = optional(registryKey, 256);
        intentSenderToken = optional(intentSenderToken, 512);
        dataToken = optional(dataToken, 512);
        resultIntent = Objects.requireNonNullElse(resultIntent, ResultIntentSnapshot.EMPTY);
        if (requestCode < 0 || requestCode > 0xffff) {
            throw new IllegalArgumentException("requestCode must be 0..65535");
        }
    }

    /** Compatibility constructor for the M4-T15 stage-1 route-token result shape. */
    public ActivityResultDelivery(
            String callerActivityToken,
            String calleeActivityToken,
            String resultWho,
            int requestCode,
            int resultCode,
            String dataToken) {
        this(callerActivityToken, calleeActivityToken, resultWho, "", requestCode,
                resultCode, "", dataToken, ResultIntentSnapshot.EMPTY);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String optional(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException("Activity result field exceeds limit");
        }
        return normalized;
    }
}
