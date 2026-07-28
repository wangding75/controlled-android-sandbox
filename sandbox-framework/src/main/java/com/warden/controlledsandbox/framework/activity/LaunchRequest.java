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
        int requestCode,
        String packageRevision,
        DocumentLaunchMode documentLaunchMode,
        String documentKey) {

    public LaunchRequest {
        identity = Objects.requireNonNull(identity, "identity");
        taskAffinity = normalize(taskAffinity, identity.packageName());
        launchMode = Objects.requireNonNull(launchMode, "launchMode");
        processName = normalize(processName, identity.packageName());
        routeToken = requireText(routeToken, "routeToken");
        resultWho = resultWho == null ? "" : resultWho;
        packageRevision = normalize(packageRevision, "legacy");
        documentLaunchMode = documentLaunchMode == null
                ? DocumentLaunchMode.NONE : documentLaunchMode;
        documentKey = documentKey == null ? "" : documentKey.trim();
        if (callerTaskId != null && callerTaskId < 1) {
            throw new IllegalArgumentException("callerTaskId must be positive");
        }
        if (processGeneration < 1) {
            throw new IllegalArgumentException("processGeneration must be positive");
        }
        if (documentKey.length() > 2048) {
            throw new IllegalArgumentException("documentKey exceeds 2048 characters");
        }
    }

    /** Backward-compatible constructor for existing source tests and legacy callers. */
    public LaunchRequest(
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
        this(identity, taskAffinity, launchMode, flags, callerTaskId, processName,
                processGeneration, routeToken, resultWho, requestCode,
                "legacy", DocumentLaunchMode.NONE, "");
    }

    public LaunchRequest withFlags(int newFlags) {
        return new LaunchRequest(identity, taskAffinity, launchMode, newFlags, callerTaskId,
                processName, processGeneration, routeToken, resultWho, requestCode,
                packageRevision, documentLaunchMode, documentKey);
    }

    public boolean documentRequested() {
        return documentLaunchMode == DocumentLaunchMode.ALWAYS
                || documentLaunchMode == DocumentLaunchMode.INTO_EXISTING
                || LaunchFlags.has(flags, LaunchFlags.NEW_DOCUMENT);
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
