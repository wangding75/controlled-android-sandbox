package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

/** Current virtual-process identity for one live Activity record. */
public record ActivityProcessIdentity(
        int virtualUserId,
        String packageName,
        String processName,
        long processGeneration) {

    public ActivityProcessIdentity {
        if (virtualUserId < 0) {
            throw new IllegalArgumentException("virtualUserId must be non-negative");
        }
        packageName = requireText(packageName, "packageName");
        processName = requireText(processName, "processName");
        if (processGeneration < 1) {
            throw new IllegalArgumentException("processGeneration must be positive");
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
