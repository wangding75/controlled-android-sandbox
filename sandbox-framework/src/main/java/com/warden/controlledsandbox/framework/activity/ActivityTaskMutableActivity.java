package com.warden.controlledsandbox.framework.activity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Internal mutable Activity state separated from ActivityTaskLedger policy. */
final class ActivityTaskMutableActivity {
    final ActivityIdentity identity;
    final String stableId;
    String token;
    final LaunchMode launchMode;
    final String processName;
    long processGeneration;
    final String resultWho;
    final int requestCode;
    final int launchFlags;
    final boolean noHistory;
    boolean restoredFromCheckpoint;
    LifecycleState lifecycleState = LifecycleState.INITIALIZED;
    long newIntentCount;
    final List<NewIntentDelivery> pendingNewIntents = new ArrayList<>();
    final List<ActivityTaskPendingResultLink> pendingResultLinks = new ArrayList<>();
    final LinkedHashMap<String, Integer> resultRegistrations = new LinkedHashMap<>();
    long recreationCount;
    SavedActivityState savedState;
    long configurationCount;
    String lastConfigurationToken = "";

    ActivityTaskMutableActivity(
            ActivityIdentity identity,
            String stableId,
            String token,
            LaunchMode launchMode,
            String processName,
            long processGeneration,
            String resultWho,
            int requestCode,
            int launchFlags,
            boolean noHistory) {
        this.identity = identity;
        this.stableId = ActivityTaskTextPolicy.requireBoundedText(stableId, "stableId", 128);
        this.token = token;
        this.launchMode = launchMode;
        this.processName = processName;
        this.processGeneration = processGeneration;
        this.resultWho = resultWho;
        this.requestCode = requestCode;
        this.launchFlags = launchFlags;
        this.noHistory = noHistory;
    }

    ActivitySnapshot snapshot() {
        return new ActivitySnapshot(
                identity,
                token,
                launchMode,
                processName,
                processGeneration,
                lifecycleState,
                resultWho,
                requestCode,
                newIntentCount,
                pendingNewIntents.size(),
                pendingResultLinks.size(),
                recreationCount,
                savedState == null ? 0 : savedState.version(),
                configurationCount,
                lastConfigurationToken);
    }


}
