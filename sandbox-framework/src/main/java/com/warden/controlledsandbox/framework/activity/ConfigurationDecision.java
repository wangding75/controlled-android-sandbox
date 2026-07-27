package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

public record ConfigurationDecision(
        ConfigurationAction action,
        String previousActivityToken,
        String currentActivityToken,
        long sequence,
        String configurationToken) {

    public ConfigurationDecision {
        action = Objects.requireNonNull(action, "action");
        previousActivityToken = requireText(previousActivityToken, "previousActivityToken");
        currentActivityToken = requireText(currentActivityToken, "currentActivityToken");
        configurationToken = requireText(configurationToken, "configurationToken");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
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
