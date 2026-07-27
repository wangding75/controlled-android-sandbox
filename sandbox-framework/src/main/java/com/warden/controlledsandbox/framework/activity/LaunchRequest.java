package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

public record LaunchRequest(
        ActivityIdentity identity,
        String taskAffinity,
        LaunchMode launchMode,
        int flags,
        Integer callerTaskId,
        String processName,
        long processGeneration,
        String routeToken,
        String resultWho,
        int requestCode) {

    public LaunchRequest {
        identity = Objects.requireNonNull(identity, "identity");
        taskAffinity = normalize(taskAffinity, identity.packageName());
        launchMode = Objects.requireNonNull(launchMode, "launchMode");
        processName = normalize(processName, identity.packageName());
        routeToken = requireText(routeToken, "routeToken");
        resultWho = resultWho == null ? "" : resultWho;
        if (callerTaskId != null && callerTaskId < 1) {
            throw new IllegalArgumentException("callerTaskId must be positive");
        }
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

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
