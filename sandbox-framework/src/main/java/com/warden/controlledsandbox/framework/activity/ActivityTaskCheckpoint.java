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

    public static final int LEGACY_SCHEMA = 1;
    public static final int PREVIOUS_SCHEMA = 2;
    /** Schema that introduced stable Activity ids and durable result ownership. */
    public static final int STABLE_ACTIVITY_SCHEMA = 3;
    /** Schema that persists virtual ActivityInfo task-reset flags. */
    public static final int TASK_RESET_SCHEMA = 4;
    /** Schema that persists the base Intent projected into RecentTaskInfo/RunningTaskInfo. */
    public static final int BASE_INTENT_SCHEMA = 5;
    /** Schema that persists epoch last-active time instead of exposing the internal sequence. */
    public static final int TASK_TIME_SCHEMA = 6;
    /** Schema that persists per-Activity affinity and allowTaskReparenting policy. */
    public static final int ACTIVITY_AFFINITY_SCHEMA = 7;
    /** Schema that persists opaque framework Activity saved-state Parcel payloads. */
    public static final int SAVED_STATE_PAYLOAD_SCHEMA = 8;
    public static final int CURRENT_SCHEMA = SAVED_STATE_PAYLOAD_SCHEMA;

    public ActivityTaskCheckpoint {
        if (schemaVersion != LEGACY_SCHEMA && schemaVersion != PREVIOUS_SCHEMA
                && schemaVersion != STABLE_ACTIVITY_SCHEMA
                && schemaVersion != TASK_RESET_SCHEMA
                && schemaVersion != BASE_INTENT_SCHEMA
                && schemaVersion != TASK_TIME_SCHEMA
                && schemaVersion != CURRENT_SCHEMA) {
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
