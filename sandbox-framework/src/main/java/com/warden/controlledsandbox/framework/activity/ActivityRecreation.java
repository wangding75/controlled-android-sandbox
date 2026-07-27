package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

/** Token rotation evidence for a recreated Activity instance. */
public record ActivityRecreation(
        String previousActivityToken,
        String currentActivityToken,
        long previousProcessGeneration,
        long currentProcessGeneration,
        RecreationReason reason) {

    public ActivityRecreation {
        previousActivityToken = requireText(previousActivityToken, "previousActivityToken");
        currentActivityToken = requireText(currentActivityToken, "currentActivityToken");
        reason = Objects.requireNonNull(reason, "reason");
        if (previousProcessGeneration < 1 || currentProcessGeneration < 1) {
            throw new IllegalArgumentException("process generations must be positive");
        }
        if (previousActivityToken.equals(currentActivityToken)) {
            throw new IllegalArgumentException("recreation must rotate the Activity token");
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
