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
        List<ActivityRestoreSnapshot> activities) {

    public TaskRestoreSnapshot {
        if (taskId < 1 || virtualUserId < 0 || lastActiveSequence < 0 || moveToFrontCount < 0) {
            throw new IllegalArgumentException("invalid task restore snapshot");
        }
        packageName = requireText(packageName, "packageName");
        packageRevision = requireText(packageRevision, "packageRevision");
        affinity = Objects.requireNonNull(affinity, "affinity");
        documentLaunchMode = documentLaunchMode == null
                ? DocumentLaunchMode.NONE : documentLaunchMode;
        documentKey = documentKey == null ? "" : documentKey;
        activities = List.copyOf(Objects.requireNonNull(activities, "activities"));
        if (activities.isEmpty()) throw new IllegalArgumentException("restored task must not be empty");
        if (!documentTask && (documentLaunchMode != DocumentLaunchMode.NONE
                || !documentKey.isEmpty())) {
            throw new IllegalArgumentException("non-document task cannot expose document metadata");
        }
        for (ActivityRestoreSnapshot activity : activities) {
            if (activity.identity().virtualUserId() != virtualUserId
                    || !activity.identity().packageName().equals(packageName)) {
                throw new SecurityException("restored Activity identity does not match task owner");
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
                lastActiveSequence, moveToFrontCount, activities);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
