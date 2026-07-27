package com.warden.controlledsandbox.contract;

final class ContractChecks {
    static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    static String optionalText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    static int nonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }

    static long nonNegative(long value, String field) {
        if (value < 0L) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }

    private ContractChecks() { }
}
