package com.warden.controlledsandbox.framework.activity;

import java.util.List;
import java.util.Objects;

public record TaskSnapshot(
        int taskId,
        int virtualUserId,
        String packageName,
        String affinity,
        boolean documentTask,
        List<ActivitySnapshot> activities) {

    public TaskSnapshot {
        if (taskId < 1 || virtualUserId < 0) {
            throw new IllegalArgumentException("invalid task identity");
        }
        packageName = Objects.requireNonNull(packageName, "packageName");
        affinity = Objects.requireNonNull(affinity, "affinity");
        activities = List.copyOf(activities);
    }
}
