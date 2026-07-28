package com.warden.controlledsandbox.framework.activity;

/** Summary of a fail-closed checkpoint restore. */
public record ActivityTaskRestoreOutcome(
        int restoredTaskCount,
        int restoredActivityCount,
        int restoredRecentTaskCount,
        int droppedTransportDeliveryCount) {
    public ActivityTaskRestoreOutcome {
        if (restoredTaskCount < 0 || restoredActivityCount < 0 || restoredRecentTaskCount < 0
                || droppedTransportDeliveryCount < 0) {
            throw new IllegalArgumentException("restore counters must be non-negative");
        }
    }
}
