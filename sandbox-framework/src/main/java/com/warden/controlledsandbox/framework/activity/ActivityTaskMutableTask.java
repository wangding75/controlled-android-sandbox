package com.warden.controlledsandbox.framework.activity;

import java.util.ArrayList;
import java.util.List;

/** Internal mutable Task state separated from ActivityTaskLedger policy. */
final class ActivityTaskMutableTask {
    final int taskId;
    final int virtualUserId;
    final String packageName;
    final String packageRevision;
    final String affinity;
    final boolean documentTask;
    final DocumentLaunchMode documentLaunchMode;
    final String documentKey;
    final int rootIntentFlags;
    final String baseIntentAction;
    final String baseIntentDataUri;
    final String baseIntentMimeType;
    final List<String> baseIntentCategories;
    final boolean excludedFromRecents;
    final boolean retainInRecents;
    long lastActiveSequence;
    long lastActiveTimeMillis;
    long moveToFrontCount;
    /** True while the durable virtual task has no corresponding live Host task after restore. */
    boolean hostTaskDetached;
    final List<ActivityTaskMutableActivity> activities = new ArrayList<>();

    ActivityTaskMutableTask(
            int taskId,
            int virtualUserId,
            String packageName,
            String packageRevision,
            String affinity,
            boolean documentTask,
            DocumentLaunchMode documentLaunchMode,
            String documentKey,
            int rootIntentFlags,
            String baseIntentAction,
            String baseIntentDataUri,
            String baseIntentMimeType,
            List<String> baseIntentCategories,
            boolean excludedFromRecents,
            boolean retainInRecents,
            long lastActiveSequence,
            long lastActiveTimeMillis) {
        this.taskId = taskId;
        this.virtualUserId = virtualUserId;
        this.packageName = packageName;
        this.packageRevision = packageRevision;
        this.affinity = affinity;
        this.documentTask = documentTask;
        this.documentLaunchMode = documentLaunchMode;
        this.documentKey = documentKey;
        this.rootIntentFlags = rootIntentFlags;
        this.baseIntentAction = baseIntentAction == null ? "" : baseIntentAction;
        this.baseIntentDataUri = baseIntentDataUri == null ? "" : baseIntentDataUri;
        this.baseIntentMimeType = baseIntentMimeType == null ? "" : baseIntentMimeType;
        this.baseIntentCategories = baseIntentCategories == null
                ? List.of() : List.copyOf(baseIntentCategories);
        this.excludedFromRecents = excludedFromRecents;
        this.retainInRecents = retainInRecents;
        this.lastActiveSequence = lastActiveSequence;
        this.lastActiveTimeMillis = lastActiveTimeMillis > 0
                ? lastActiveTimeMillis : System.currentTimeMillis();
    }

    TaskSnapshot snapshot() {
        return new TaskSnapshot(
                taskId,
                virtualUserId,
                packageName,
                affinity,
                documentTask,
                activities.stream().map(ActivityTaskMutableActivity::snapshot)
                        .collect(java.util.stream.Collectors.toList()));
    }
}
