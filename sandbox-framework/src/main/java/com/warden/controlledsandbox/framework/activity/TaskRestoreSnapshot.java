package com.warden.controlledsandbox.framework.activity;

import java.util.List;
import java.util.Objects;

/** Durable task/back-stack metadata owned by the Runtime Broker. */
public record TaskRestoreSnapshot(
        int taskId,
        int virtualUserId,
        String packageName,
        String packageRevision,
        String affinity,
        boolean documentTask,
        DocumentLaunchMode documentLaunchMode,
        String documentKey,
        int rootIntentFlags,
        boolean excludedFromRecents,
        boolean retainInRecents,
        long lastActiveSequence,
        long moveToFrontCount,
        String baseIntentAction,
        String baseIntentDataUri,
        String baseIntentMimeType,
        List<String> baseIntentCategories,
        long lastActiveTimeMillis,
        List<ActivityRestoreSnapshot> activities) {

    public TaskRestoreSnapshot {
        if (taskId < 1 || virtualUserId < 0 || lastActiveSequence < 0 || moveToFrontCount < 0
                || lastActiveTimeMillis < 0) {
            throw new IllegalArgumentException("invalid task restore snapshot");
        }
        packageName = requireText(packageName, "packageName");
        packageRevision = requireText(packageRevision, "packageRevision");
        affinity = Objects.requireNonNull(affinity, "affinity");
        documentLaunchMode = documentLaunchMode == null
                ? DocumentLaunchMode.NONE : documentLaunchMode;
        documentKey = documentKey == null ? "" : documentKey;
        baseIntentAction = optional(baseIntentAction, 512, "baseIntentAction");
        baseIntentDataUri = optional(baseIntentDataUri, 4096, "baseIntentDataUri");
        baseIntentMimeType = optional(baseIntentMimeType, 255, "baseIntentMimeType");
        baseIntentCategories = baseIntentCategories == null
                ? List.of() : List.copyOf(baseIntentCategories);
        if (baseIntentCategories.size() > 64) {
            throw new IllegalArgumentException("baseIntentCategories exceeds 64 values");
        }
        activities = List.copyOf(Objects.requireNonNull(activities, "activities"));
        if (activities.isEmpty()) throw new IllegalArgumentException("restored task must not be empty");
        if (!documentTask && (documentLaunchMode != DocumentLaunchMode.NONE
                || !documentKey.isEmpty())) {
            throw new IllegalArgumentException("non-document task cannot expose document metadata");
        }
        for (ActivityRestoreSnapshot activity : activities) {
            if (activity.identity().virtualUserId() != virtualUserId) {
                throw new SecurityException("restored Activity virtual user does not match task");
            }
        }
    }

    /** Schema-1 compatibility constructor. */
    public TaskRestoreSnapshot(
            int taskId,
            int virtualUserId,
            String packageName,
            String affinity,
            boolean documentTask,
            int rootIntentFlags,
            boolean excludedFromRecents,
            boolean retainInRecents,
            long lastActiveSequence,
            long moveToFrontCount,
            List<ActivityRestoreSnapshot> activities) {
        this(taskId, virtualUserId, packageName, "legacy", affinity, documentTask,
                documentTask ? DocumentLaunchMode.ALWAYS : DocumentLaunchMode.NONE,
                "", rootIntentFlags, excludedFromRecents, retainInRecents,
                lastActiveSequence, moveToFrontCount, "", "", "", List.of(), 0L, activities);
    }

    /** Compatibility constructor for checkpoints before base Intent metadata was durable. */
    public TaskRestoreSnapshot(
            int taskId,
            int virtualUserId,
            String packageName,
            String packageRevision,
            String affinity,
            boolean documentTask,
            DocumentLaunchMode documentLaunchMode,
            String documentKey,
            int rootIntentFlags,
            boolean excludedFromRecents,
            boolean retainInRecents,
            long lastActiveSequence,
            long moveToFrontCount,
            List<ActivityRestoreSnapshot> activities) {
        this(taskId, virtualUserId, packageName, packageRevision, affinity, documentTask,
                documentLaunchMode, documentKey, rootIntentFlags, excludedFromRecents,
                retainInRecents, lastActiveSequence, moveToFrontCount,
                "", "", "", List.of(), 0L, activities);
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
