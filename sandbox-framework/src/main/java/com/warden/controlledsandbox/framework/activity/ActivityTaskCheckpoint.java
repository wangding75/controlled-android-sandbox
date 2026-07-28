package com.warden.controlledsandbox.framework.activity;

import java.util.List;
import java.util.Objects;

/** Versioned Broker-process checkpoint for virtual tasks and recent-task history. */
public record ActivityTaskCheckpoint(
        int schemaVersion,
        int nextTaskId,
        long nextNewIntentSequence,
        long nextConfigurationSequence,
        long nextActivationSequence,
        int transportDeliveryCount,
        List<TaskRestoreSnapshot> tasks,
        List<TaskQuerySnapshot> recentTasks) {

    public static final int CURRENT_SCHEMA = 1;

    public ActivityTaskCheckpoint {
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("unsupported Activity task checkpoint schema: " + schemaVersion);
        }
        if (nextTaskId < 1 || nextNewIntentSequence < 1 || nextConfigurationSequence < 1
                || nextActivationSequence < 1 || transportDeliveryCount < 0) {
            throw new IllegalArgumentException("invalid Activity task checkpoint counters");
        }
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        recentTasks = List.copyOf(Objects.requireNonNull(recentTasks, "recentTasks"));
    }

    public static ActivityTaskCheckpoint empty() {
        return new ActivityTaskCheckpoint(CURRENT_SCHEMA, 1, 1, 1, 1, 0, List.of(), List.of());
    }
}
