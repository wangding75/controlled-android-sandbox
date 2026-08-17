package com.warden.controlledsandbox.framework.activity;

import com.warden.controlledsandbox.framework.routing.RouteOwner;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Launch input before a one-time transport token has been allocated. */
public record ActivityLaunchSpec(
        ActivityIdentity identity,
        String taskAffinity,
        LaunchMode launchMode,
        int flags,
        Integer callerTaskId,
        String processName,
        long processGeneration,
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

    public ActivityLaunchSpec {
        identity = Objects.requireNonNull(identity, "identity");
        taskAffinity = normalize(taskAffinity, identity.packageName());
        launchMode = Objects.requireNonNull(launchMode, "launchMode");
        processName = normalize(processName, identity.packageName());
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
    }


    /** Compatibility constructor for the M4-T15 B1 typed task shape. */
    public ActivityLaunchSpec(
            ActivityIdentity identity,
            String taskAffinity,
            LaunchMode launchMode,
            int flags,
            Integer callerTaskId,
            String processName,
            long processGeneration,
            String resultWho,
            int requestCode,
            String packageRevision,
            DocumentLaunchMode documentLaunchMode,
            String documentKey) {
        this(identity, taskAffinity, launchMode, flags, callerTaskId, processName,
                processGeneration, resultWho, requestCode, packageRevision,
                documentLaunchMode, documentKey, "", "", 0);
    }

    /** Compatibility constructor for the M4-T14/M4-T15 stage-1 call shape. */
    public ActivityLaunchSpec(
            ActivityIdentity identity,
            String taskAffinity,
            LaunchMode launchMode,
            int flags,
            Integer callerTaskId,
            String processName,
            long processGeneration,
            String resultWho,
            int requestCode) {
        this(identity, taskAffinity, launchMode, flags, callerTaskId, processName,
                processGeneration, resultWho, requestCode, "legacy",
                DocumentLaunchMode.NONE, "", "", "", 0);
    }

    /** Compatibility constructor for the pre-base-Intent task shape with ActivityInfo flags. */
    public ActivityLaunchSpec(
            ActivityIdentity identity,
            String taskAffinity,
            LaunchMode launchMode,
            int flags,
            Integer callerTaskId,
            String processName,
            long processGeneration,
            String resultWho,
            int requestCode,
            String packageRevision,
            DocumentLaunchMode documentLaunchMode,
            String documentKey,
            String activityResultKey,
            String intentSenderToken,
            int activityInfoFlags) {
        this(identity, taskAffinity, launchMode, flags, callerTaskId, processName,
                processGeneration, resultWho, requestCode, packageRevision,
                documentLaunchMode, documentKey, activityResultKey, intentSenderToken,
                activityInfoFlags, "", "", "", List.of());
    }

    public RouteOwner routeOwner() {
        return new RouteOwner(
                identity.virtualUserId(),
                identity.packageName(),
                processName,
                processGeneration);
    }

    LaunchRequest toLaunchRequest(String routeToken) {
        return new LaunchRequest(
                identity,
                taskAffinity,
                launchMode,
                flags,
                callerTaskId,
                processName,
                processGeneration,
                routeToken,
                resultWho,
                requestCode,
                packageRevision,
                documentLaunchMode,
                documentKey,
                activityResultKey,
                intentSenderToken,
                activityInfoFlags,
                intentAction,
                intentDataUri,
                intentMimeType,
                intentCategories);
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
