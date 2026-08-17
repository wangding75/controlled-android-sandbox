package com.warden.controlledsandbox.framework.activity;

import java.util.List;
import java.util.Objects;

/** Durable Activity metadata. Completed transport deliveries remain intentionally absent. */
public record ActivityRestoreSnapshot(
        ActivityIdentity identity,
        String stableId,
        LaunchMode launchMode,
        String processName,
        long processGeneration,
        String resultWho,
        int requestCode,
        int launchFlags,
        int activityInfoFlags,
        boolean noHistory,
        long newIntentCount,
        long recreationCount,
        SavedActivityState savedState,
        long configurationCount,
        String lastConfigurationToken,
        List<ActivityResultRegistration> resultRegistrations,
        List<PendingActivityResultSnapshot> pendingResultLinks,
        String taskAffinity,
        boolean allowTaskReparenting) {

    public ActivityRestoreSnapshot {
        identity = Objects.requireNonNull(identity, "identity");
        stableId = optional(stableId, 128);
        launchMode = Objects.requireNonNull(launchMode, "launchMode");
        processName = requireText(processName, "processName");
        resultWho = optional(resultWho, 256);
        lastConfigurationToken = optional(lastConfigurationToken, 1024);
        taskAffinity = optional(taskAffinity, 255);
        resultRegistrations = List.copyOf(resultRegistrations == null ? List.of() : resultRegistrations);
        pendingResultLinks = List.copyOf(pendingResultLinks == null ? List.of() : pendingResultLinks);
        if (resultRegistrations.size() > 128 || pendingResultLinks.size() > 128) {
            throw new IllegalArgumentException("too many durable Activity result entries");
        }
        if (processGeneration < 1 || newIntentCount < 0 || recreationCount < 0
                || configurationCount < 0) {
            throw new IllegalArgumentException("invalid Activity restore snapshot");
        }
        if (activityInfoFlags < 0) {
            throw new IllegalArgumentException("activityInfoFlags must be non-negative");
        }
    }

    /** Compatibility constructor for checkpoints before per-Activity affinity was durable. */
    public ActivityRestoreSnapshot(
            ActivityIdentity identity,
            String stableId,
            LaunchMode launchMode,
            String processName,
            long processGeneration,
            String resultWho,
            int requestCode,
            int launchFlags,
            int activityInfoFlags,
            boolean noHistory,
            long newIntentCount,
            long recreationCount,
            SavedActivityState savedState,
            long configurationCount,
            String lastConfigurationToken,
            List<ActivityResultRegistration> resultRegistrations,
            List<PendingActivityResultSnapshot> pendingResultLinks) {
        this(identity, stableId, launchMode, processName, processGeneration, resultWho, requestCode,
                launchFlags, activityInfoFlags, noHistory, newIntentCount, recreationCount,
                savedState, configurationCount, lastConfigurationToken, resultRegistrations,
                pendingResultLinks, identity.packageName(),
                ActivityInfoTaskFlags.has(activityInfoFlags, ActivityInfoTaskFlags.ALLOW_TASK_REPARENTING));
    }

    /** Compatibility constructor for schema 1/2 checkpoints. */
    public ActivityRestoreSnapshot(
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
        this(identity, "", launchMode, processName, processGeneration, resultWho, requestCode,
                launchFlags, 0, noHistory, newIntentCount, recreationCount, savedState,
                configurationCount, lastConfigurationToken, List.of(), List.of(),
                identity.packageName(), false);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static String optional(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) throw new IllegalArgumentException("value exceeds limit");
        return normalized;
    }
}
