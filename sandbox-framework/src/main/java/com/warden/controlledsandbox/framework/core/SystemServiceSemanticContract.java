package com.warden.controlledsandbox.framework.core;

import java.util.Locale;

/** Human- and machine-readable ownership contract for one framework service boundary. */
public record SystemServiceSemanticContract(
        String service,
        String ownership,
        String identityTransformation,
        String callbackPath,
        String lifecycle,
        Status status) {

    public enum Status { CLOSED, PARTIAL, DEFERRED, OUT_OF_SCOPE }

    public SystemServiceSemanticContract {
        service = required(service, "service").toLowerCase(Locale.ROOT);
        ownership = required(ownership, "ownership");
        identityTransformation = required(identityTransformation, "identityTransformation");
        callbackPath = required(callbackPath, "callbackPath");
        lifecycle = required(lifecycle, "lifecycle");
        if (status == null) throw new IllegalArgumentException("status is required");
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
