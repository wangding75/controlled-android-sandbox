package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

/** Durable ownership metadata for an unfinished startActivityForResult chain. */
public record PendingActivityResultSnapshot(
        String callerStableId,
        String resultWho,
        String registryKey,
        int requestCode,
        String intentSenderToken) {
    public PendingActivityResultSnapshot {
        callerStableId = required(callerStableId, "callerStableId", 128);
        resultWho = optional(resultWho, 256);
        registryKey = optional(registryKey, 256);
        intentSenderToken = optional(intentSenderToken, 512);
        if (requestCode < 0 || requestCode > 0xffff) {
            throw new IllegalArgumentException("requestCode must be 0..65535");
        }
    }

    private static String required(String value, String name, int maximum) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    private static String optional(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) throw new IllegalArgumentException("value too long");
        return normalized;
    }
}
