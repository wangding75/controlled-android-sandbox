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
        int requestCode,
        String packageRevision,
        DocumentLaunchMode documentLaunchMode,
        String documentKey) {

    public ActivityLaunchSpec {
        identity = Objects.requireNonNull(identity, "identity");
        taskAffinity = normalize(taskAffinity, identity.packageName());
        launchMode = Objects.requireNonNull(launchMode, "launchMode");
        processName = normalize(processName, identity.packageName());
        resultWho = resultWho == null ? "" : resultWho;
        packageRevision = normalize(packageRevision, "legacy");
        documentLaunchMode = documentLaunchMode == null
                ? DocumentLaunchMode.NONE : documentLaunchMode;
        documentKey = documentKey == null ? "" : documentKey.trim();
        if (callerTaskId != null && callerTaskId < 1) {
            throw new IllegalArgumentException("callerTaskId must be positive");
        }
        if (processGeneration < 1) {
            throw new IllegalArgumentException("processGeneration must be positive");
        }
    }

    /** Compatibility constructor for the M4-T14/M4-T15 stage-1 call shape. */
    public ActivityLaunchSpec(
            ActivityIdentity identity,
            String taskAffinity,
            LaunchMode launchMode,
            int flags,
            Integer callerTaskId,
            String processName,
            long processGeneration,
            String resultWho,
            int requestCode) {
        this(identity, taskAffinity, launchMode, flags, callerTaskId, processName,
                processGeneration, resultWho, requestCode, "legacy",
                DocumentLaunchMode.NONE, "");
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
                requestCode,
                packageRevision,
                documentLaunchMode,
                documentKey);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
