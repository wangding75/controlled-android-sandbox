package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

/** Immutable result envelope waiting for delivery to a caller Activity. */
public record ActivityResultDelivery(
        String callerActivityToken,
        String calleeActivityToken,
        String resultWho,
        int requestCode,
        int resultCode,
        String dataToken) {

    public ActivityResultDelivery {
        callerActivityToken = requireText(callerActivityToken, "callerActivityToken");
        calleeActivityToken = requireText(calleeActivityToken, "calleeActivityToken");
        resultWho = resultWho == null ? "" : resultWho;
        dataToken = dataToken == null ? "" : dataToken;
        if (requestCode < 0) {
            throw new IllegalArgumentException("requestCode must be non-negative");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
