package com.warden.controlledsandbox.framework.activity;

import com.warden.controlledsandbox.framework.routing.RouteOwner;
import java.util.Objects;

/** Launch input before a one-time transport token has been allocated. */
public record ActivityLaunchSpec(
        ActivityIdentity identity,
        String taskAffinity,
        LaunchMode launchMode,
        int flags,
        Integer callerTaskId,
        String processName,
        long processGeneration,
        String resultWho,
        int requestCode) {

    public ActivityLaunchSpec {
        identity = Objects.requireNonNull(identity, "identity");
        taskAffinity = normalize(taskAffinity, identity.packageName());
        launchMode = Objects.requireNonNull(launchMode, "launchMode");
        processName = normalize(processName, identity.packageName());
        resultWho = resultWho == null ? "" : resultWho;
        if (callerTaskId != null && callerTaskId < 1) {
            throw new IllegalArgumentException("callerTaskId must be positive");
        }
        if (processGeneration < 1) {
            throw new IllegalArgumentException("processGeneration must be positive");
        }
    }

    public RouteOwner routeOwner() {
        return new RouteOwner(
                identity.virtualUserId(),
                identity.packageName(),
                processName,
                processGeneration);
    }

    LaunchRequest toLaunchRequest(String routeToken) {
        return new LaunchRequest(
                identity,
                taskAffinity,
                launchMode,
                flags,
                callerTaskId,
                processName,
                processGeneration,
                routeToken,
                resultWho,
                requestCode);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
