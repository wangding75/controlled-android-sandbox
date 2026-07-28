package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

/** Durable Activity metadata. Transport tokens and pending Binder deliveries are intentionally absent. */
public record ActivityRestoreSnapshot(
        ActivityIdentity identity,
        LaunchMode launchMode,
        String processName,
        long processGeneration,
        String resultWho,
        int requestCode,
        int launchFlags,
        boolean noHistory,
        long newIntentCount,
        long recreationCount,
        SavedActivityState savedState,
        long configurationCount,
        String lastConfigurationToken) {

    public ActivityRestoreSnapshot {
        identity = Objects.requireNonNull(identity, "identity");
        launchMode = Objects.requireNonNull(launchMode, "launchMode");
        processName = requireText(processName, "processName");
        resultWho = resultWho == null ? "" : resultWho;
        lastConfigurationToken = lastConfigurationToken == null ? "" : lastConfigurationToken;
        if (processGeneration < 1 || newIntentCount < 0 || recreationCount < 0
                || configurationCount < 0) {
            throw new IllegalArgumentException("invalid Activity restore snapshot");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
