package com.warden.controlledsandbox.runtime.component.activity;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.framework.activity.ActivityProcessIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import com.warden.controlledsandbox.framework.activity.ActivityRecreation;
import com.warden.controlledsandbox.framework.activity.ConfigurationDecision;
import com.warden.controlledsandbox.framework.activity.LifecycleState;
import com.warden.controlledsandbox.framework.activity.ProcessRecreationOutcome;
import com.warden.controlledsandbox.framework.activity.SavedActivityState;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.LinkedHashMap;
import java.util.Map;

/** Applies Activity lifecycle events and process-generation transitions atomically. */
final class ActivityRuntimeLifecycleCoordinator {
    private final ActivityTaskLedger ledger;
    private final ActivityRuntimeRouteCoordinator routes;
    private final ActivityCheckpointTransaction transactions;
    private final Runnable persistCheckpoint;

    ActivityRuntimeLifecycleCoordinator(
            ActivityTaskLedger ledger,
            ActivityRuntimeRouteCoordinator routes,
            ActivityCheckpointTransaction transactions,
            Runnable persistCheckpoint) {
        this.ledger = ledger;
        this.routes = routes;
        this.transactions = transactions;
        this.persistCheckpoint = persistCheckpoint;
    }

    Bundle event(GuestSession session, Bundle request) {
        if (request == null) throw new IllegalArgumentException("activity event request is required");
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            String activityToken = required(request, RuntimeKeys.ACTIVITY_TOKEN);
            routes.verifyOwner(activityToken, session);
            String event = required(request, RuntimeKeys.ACTIVITY_EVENT);
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "ACTIVITY_EVENT_APPLIED");
            out.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
            switch (event) {
                case "CREATED" -> ledger.transition(activityToken, LifecycleState.CREATED);
                case "STARTED" -> ledger.transition(activityToken, LifecycleState.STARTED);
                case "RESUMED" -> ledger.transition(activityToken, LifecycleState.RESUMED);
                case "PAUSED" -> ledger.transition(activityToken, LifecycleState.PAUSED);
                case "STOPPED" -> ledger.transition(activityToken, LifecycleState.STOPPED);
                case "DESTROYED" -> ledger.transition(activityToken, LifecycleState.DESTROYED);
                case "SAVE_STATE" -> ledger.saveInstanceState(activityToken,
                        new SavedActivityState(request.getLong(RuntimeKeys.SAVED_STATE_VERSION, 1L),
                                savedState(request)));
                case "CONFIGURATION" -> {
                    ConfigurationDecision decision = ledger.handleConfigurationChange(
                            activityToken, required(request, RuntimeKeys.CONFIGURATION_TOKEN),
                            request.getBoolean(RuntimeKeys.HANDLES_CONFIGURATION, true));
                    out.putString(RuntimeKeys.ACTIVITY_TOKEN, decision.currentActivityToken());
                    out.putString(RuntimeKeys.ACTIVITY_ACTION, decision.action().name());
                }
                case "FINISH_RESULT" -> ledger.finishWithResult(
                        activityToken,
                        request.getInt(RuntimeKeys.RESULT_CODE, ActivityTaskLedger.RESULT_CANCELED),
                        ActivityResultBundleCodec.decode(request));
                default -> throw new IllegalArgumentException("Unknown activity event: " + event);
            }
            if ("DESTROYED".equals(event) || "FINISH_RESULT".equals(event)) {
                routes.releaseActivityRoute(activityToken);
            }
            out.putInt(RuntimeKeys.ACTIVITY_COUNT, ledger.activityCount());
            out.putInt(RuntimeKeys.TASK_COUNT, ledger.taskCount());
            persistCheckpoint.run();
            return out;
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    void recreate(GuestSession stale, GuestSession current) {
        final ProcessRecreationOutcome[] outcome = new ProcessRecreationOutcome[1];
        transactions.mutate(() -> outcome[0] = routes.coordinator().recreateProcessGeneration(
                stale.virtualUserId(), stale.packageName(), stale.processName(),
                stale.generation(), current.generation()));
        routes.rebindTransactions(stale, current, outcome[0].recreations());
    }

    void processDisconnected(GuestSession stale) { routes.processDisconnected(stale); }

    void invalidate(GuestSession stale) {
        transactions.mutate(() -> routes.coordinator().invalidateProcessGeneration(
                stale.virtualUserId(), stale.packageName(), stale.processName(), stale.generation()));
        routes.purgePending(stale);
    }

    private static Map<String, String> savedState(Bundle request) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : request.keySet()) {
            if (key.startsWith(RuntimeKeys.SAVED_STATE_PREFIX)) {
                Object value = request.get(key);
                if (value != null) values.put(key.substring(RuntimeKeys.SAVED_STATE_PREFIX.length()), String.valueOf(value));
            }
        }
        return values;
    }

    private static String required(Bundle value, String key) {
        String result = value.getString(key, "");
        if (result.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return result;
    }
}
