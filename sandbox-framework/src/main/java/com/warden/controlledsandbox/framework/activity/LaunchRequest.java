package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

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
        String documentKey,
        String activityResultKey,
        String intentSenderToken,
        int activityInfoFlags,
        String intentAction,
        String intentDataUri,
        String intentMimeType,
        List<String> intentCategories) {

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
        activityResultKey = optional(activityResultKey, 256, "activityResultKey");
        intentSenderToken = optional(intentSenderToken, 512, "intentSenderToken");
        intentAction = optional(intentAction, 512, "intentAction");
        intentDataUri = optional(intentDataUri, 4096, "intentDataUri");
        intentMimeType = optional(intentMimeType, 255, "intentMimeType");
        ArrayList<String> normalizedCategories = new ArrayList<>();
        if (intentCategories != null) {
            if (intentCategories.size() > 64) {
                throw new IllegalArgumentException("intentCategories exceeds 64 values");
            }
            for (String category : intentCategories) {
                String normalized = optional(category, 255, "intentCategory");
                if (!normalized.isEmpty() && !normalizedCategories.contains(normalized)) {
                    normalizedCategories.add(normalized);
                }
            }
        }
        intentCategories = List.copyOf(normalizedCategories);
        if (activityInfoFlags < 0) {
            throw new IllegalArgumentException("activityInfoFlags must be non-negative");
        }
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

    /** Compatibility constructor for callers compiled against the pre-task-reset shape. */
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
            int requestCode,
            String packageRevision,
            DocumentLaunchMode documentLaunchMode,
            String documentKey,
            String activityResultKey,
            String intentSenderToken) {
        this(identity, taskAffinity, launchMode, flags, callerTaskId, processName,
                processGeneration, routeToken, resultWho, requestCode, packageRevision,
                documentLaunchMode, documentKey, activityResultKey, intentSenderToken, 0);
    }

    /** Compatibility constructor for the pre-base-Intent task shape with ActivityInfo flags. */
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
            int requestCode,
            String packageRevision,
            DocumentLaunchMode documentLaunchMode,
            String documentKey,
            String activityResultKey,
            String intentSenderToken,
            int activityInfoFlags) {
        this(identity, taskAffinity, launchMode, flags, callerTaskId, processName,
                processGeneration, routeToken, resultWho, requestCode, packageRevision,
                documentLaunchMode, documentKey, activityResultKey, intentSenderToken,
                activityInfoFlags, "", "", "", List.of());
    }


    /** Compatibility constructor for the M4-T15 B1 typed task shape. */
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
            int requestCode,
            String packageRevision,
            DocumentLaunchMode documentLaunchMode,
            String documentKey) {
        this(identity, taskAffinity, launchMode, flags, callerTaskId, processName,
                processGeneration, routeToken, resultWho, requestCode, packageRevision,
                documentLaunchMode, documentKey, "", "", 0);
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
                "legacy", DocumentLaunchMode.NONE, "", "", "", 0);
    }

    public LaunchRequest withFlags(int newFlags) {
        return new LaunchRequest(identity, taskAffinity, launchMode, newFlags, callerTaskId,
                processName, processGeneration, routeToken, resultWho, requestCode,
                packageRevision, documentLaunchMode, documentKey, activityResultKey, intentSenderToken,
                activityInfoFlags, intentAction, intentDataUri, intentMimeType, intentCategories);
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

    private static String optional(String value, int maximum, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " characters");
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
