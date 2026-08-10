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
    final boolean excludedFromRecents;
    final boolean retainInRecents;
    long lastActiveSequence;
    long moveToFrontCount;
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
            boolean excludedFromRecents,
            boolean retainInRecents,
            long lastActiveSequence) {
        this.taskId = taskId;
        this.virtualUserId = virtualUserId;
        this.packageName = packageName;
        this.packageRevision = packageRevision;
        this.affinity = affinity;
        this.documentTask = documentTask;
        this.documentLaunchMode = documentLaunchMode;
        this.documentKey = documentKey;
        this.rootIntentFlags = rootIntentFlags;
        this.excludedFromRecents = excludedFromRecents;
        this.retainInRecents = retainInRecents;
        this.lastActiveSequence = lastActiveSequence;
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
