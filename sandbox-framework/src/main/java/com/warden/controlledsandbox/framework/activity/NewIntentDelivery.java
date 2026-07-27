package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

/** Metadata for a pending onNewIntent callback. The actual Intent remains in one-time route storage. */
public record NewIntentDelivery(
        String activityToken,
        long sequence,
        String routeToken,
        int flags,
        Integer sourceTaskId,
        String resultWho,
        int requestCode) {

    public NewIntentDelivery {
        activityToken = requireText(activityToken, "activityToken");
        routeToken = requireText(routeToken, "routeToken");
        resultWho = resultWho == null ? "" : resultWho;
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (sourceTaskId != null && sourceTaskId < 1) {
            throw new IllegalArgumentException("sourceTaskId must be positive");
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
