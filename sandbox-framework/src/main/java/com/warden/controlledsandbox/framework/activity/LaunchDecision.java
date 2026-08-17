package com.warden.controlledsandbox.framework.activity;


import java.util.Objects;

public record LaunchDecision(
        LaunchAction action,
        int taskId,
        String activityToken,
        String routeToken,
        int removedActivityCount,
        boolean createdNewTask,
        boolean hostTaskRebindRequired) {

    public LaunchDecision {
        action = Objects.requireNonNull(action, "action");
        if (taskId < 1) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        activityToken = requireText(activityToken, "activityToken");
        routeToken = requireText(routeToken, "routeToken");
        if (removedActivityCount < 0) {
            throw new IllegalArgumentException("removedActivityCount must be non-negative");
        }
    }

    /** Compatibility constructor for callers that do not carry Host-task attachment state. */
    public LaunchDecision(
            LaunchAction action,
            int taskId,
            String activityToken,
            String routeToken,
            int removedActivityCount,
            boolean createdNewTask) {
        this(action, taskId, activityToken, routeToken, removedActivityCount,
                createdNewTask, false);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
