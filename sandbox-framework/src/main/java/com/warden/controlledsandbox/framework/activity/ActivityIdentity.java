package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

public record ActivityIdentity(
        int virtualUserId,
        String packageName,
        String componentName) {

    public ActivityIdentity {
        if (virtualUserId < 0) {
            throw new IllegalArgumentException("virtualUserId must be non-negative");
        }
        packageName = requireText(packageName, "packageName");
        componentName = requireText(componentName, "componentName");
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
