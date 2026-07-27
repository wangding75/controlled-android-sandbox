package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

public record ActivitySnapshot(
        ActivityIdentity identity,
        String token,
        LaunchMode launchMode,
        String processName,
        long processGeneration,
        LifecycleState lifecycleState,
        String resultWho,
        int requestCode,
        long newIntentCount,
        int pendingNewIntentCount,
        int pendingResultCount,
        long recreationCount,
        long savedStateVersion,
        long configurationCount,
        String lastConfigurationToken) {

    public ActivitySnapshot {
        identity = Objects.requireNonNull(identity, "identity");
        token = Objects.requireNonNull(token, "token");
        launchMode = Objects.requireNonNull(launchMode, "launchMode");
        processName = Objects.requireNonNull(processName, "processName");
        lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        lastConfigurationToken = lastConfigurationToken == null ? "" : lastConfigurationToken;
        if (processGeneration < 1
                || newIntentCount < 0
                || pendingNewIntentCount < 0
                || pendingResultCount < 0
                || recreationCount < 0
                || savedStateVersion < 0
                || configurationCount < 0) {
            throw new IllegalArgumentException("invalid Activity snapshot counters");
        }
    }
}
