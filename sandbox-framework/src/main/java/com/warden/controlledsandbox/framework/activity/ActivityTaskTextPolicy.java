package com.warden.controlledsandbox.framework.activity;

/** Shared bounded text normalization for Activity/Task runtime state. */
final class ActivityTaskTextPolicy {
    private ActivityTaskTextPolicy() { }

    static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    static String requireBoundedText(String value, String name, int maximum) {
        String normalized = requireText(value, name);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " characters");
        }
        return normalized;
    }

    static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }
}
