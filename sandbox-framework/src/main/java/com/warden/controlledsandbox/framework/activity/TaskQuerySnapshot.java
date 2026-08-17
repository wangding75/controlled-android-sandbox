package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;
import java.util.List;

/** Bounded virtual task projection used for running/recent-task queries. */
public record TaskQuerySnapshot(
        int taskId,
        int virtualUserId,
        String packageName,
        String packageRevision,
        String affinity,
        boolean documentTask,
        DocumentLaunchMode documentLaunchMode,
        String documentKey,
        boolean active,
        boolean excludedFromRecents,
        boolean retainInRecents,
        int activityCount,
        String baseComponentName,
        String topComponentName,
        long lastActiveSequence,
        long moveToFrontCount,
        int baseIntentFlags,
        String baseIntentAction,
        String baseIntentDataUri,
        String baseIntentMimeType,
        List<String> baseIntentCategories,
        long lastActiveTimeMillis) {

    public TaskQuerySnapshot {
        if (taskId < 1 || virtualUserId < 0 || activityCount < 0
                || lastActiveSequence < 0 || moveToFrontCount < 0 || lastActiveTimeMillis < 0) {
            throw new IllegalArgumentException("invalid task query snapshot");
        }
        packageName = requireText(packageName, "packageName");
        packageRevision = requireText(packageRevision, "packageRevision");
        affinity = Objects.requireNonNull(affinity, "affinity");
        documentLaunchMode = documentLaunchMode == null
                ? DocumentLaunchMode.NONE : documentLaunchMode;
        documentKey = documentKey == null ? "" : documentKey;
        baseComponentName = baseComponentName == null ? "" : baseComponentName;
        topComponentName = topComponentName == null ? "" : topComponentName;
        baseIntentAction = optional(baseIntentAction, 512, "baseIntentAction");
        baseIntentDataUri = optional(baseIntentDataUri, 4096, "baseIntentDataUri");
        baseIntentMimeType = optional(baseIntentMimeType, 255, "baseIntentMimeType");
        baseIntentCategories = baseIntentCategories == null
                ? List.of() : List.copyOf(baseIntentCategories);
        if (baseIntentCategories.size() > 64) {
            throw new IllegalArgumentException("baseIntentCategories exceeds 64 values");
        }
        if (active && activityCount < 1) {
            throw new IllegalArgumentException("active task must contain an Activity");
        }
        if (!documentTask && (documentLaunchMode != DocumentLaunchMode.NONE
                || !documentKey.isEmpty())) {
            throw new IllegalArgumentException("non-document task cannot expose document metadata");
        }
    }

    /** Schema-1 compatibility constructor. */
    public TaskQuerySnapshot(
            int taskId,
            int virtualUserId,
            String packageName,
            String affinity,
            boolean documentTask,
            boolean active,
            boolean excludedFromRecents,
            boolean retainInRecents,
            int activityCount,
            String baseComponentName,
            String topComponentName,
            long lastActiveSequence,
            long moveToFrontCount) {
        this(taskId, virtualUserId, packageName, "legacy", affinity, documentTask,
                documentTask ? DocumentLaunchMode.ALWAYS : DocumentLaunchMode.NONE,
                "", active, excludedFromRecents, retainInRecents, activityCount,
                baseComponentName, topComponentName, lastActiveSequence, moveToFrontCount,
                0, "", "", "", List.of(), 0L);
    }

    /** Compatibility constructor for task projections before base Intent metadata was durable. */
    public TaskQuerySnapshot(
            int taskId,
            int virtualUserId,
            String packageName,
            String packageRevision,
            String affinity,
            boolean documentTask,
            DocumentLaunchMode documentLaunchMode,
            String documentKey,
            boolean active,
            boolean excludedFromRecents,
            boolean retainInRecents,
            int activityCount,
            String baseComponentName,
            String topComponentName,
            long lastActiveSequence,
            long moveToFrontCount) {
        this(taskId, virtualUserId, packageName, packageRevision, affinity, documentTask,
                documentLaunchMode, documentKey, active, excludedFromRecents,
                retainInRecents, activityCount, baseComponentName, topComponentName,
                lastActiveSequence, moveToFrontCount, 0, "", "", "", List.of(), 0L);
    }

    private static String optional(String value, int maximum, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " characters");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
