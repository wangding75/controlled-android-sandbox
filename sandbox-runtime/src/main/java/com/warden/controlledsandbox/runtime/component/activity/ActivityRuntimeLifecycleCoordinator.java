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
        String activityToken = "";
        String event = "";
        try {
            activityToken = required(request, RuntimeKeys.ACTIVITY_TOKEN);
            event = required(request, RuntimeKeys.ACTIVITY_EVENT);
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "ACTIVITY_EVENT_APPLIED");
            out.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
            if (!ledger.containsActivity(activityToken)) {
                if (isFrameworkLifecycleEvent(request, event)) {
                    out.putBoolean("staleLifecycleEventIgnored", true);
                    out.putInt(RuntimeKeys.ACTIVITY_COUNT, ledger.activityCount());
                    out.putInt(RuntimeKeys.TASK_COUNT, ledger.taskCount());
                    android.util.Log.i("CS_ACTIVITY_EVENT",
                            "stale lifecycle event ignored event=" + event
                                    + " activityToken=" + activityToken);
                    return out;
                }
                routes.verifyOwner(activityToken, session);
            } else {
                routes.verifyOwner(activityToken, session);
            }
            switch (event) {
                case "CREATED" -> ledger.transition(activityToken, LifecycleState.CREATED);
                case "STARTED" -> ledger.transition(activityToken, LifecycleState.STARTED);
                case "RESUMED" -> ledger.transition(activityToken, LifecycleState.RESUMED);
                case "PAUSED" -> ledger.transition(activityToken, LifecycleState.PAUSED);
                case "STOPPED" -> ledger.transition(activityToken, LifecycleState.STOPPED);
                case "DESTROYED" -> ledger.transition(activityToken, LifecycleState.DESTROYED);
                case "NEW_INTENT" -> out.putBoolean("newIntentAcknowledged",
                        ledger.acknowledgeNewIntent(activityToken,
                                required(request, RuntimeKeys.ROUTE_TOKEN)));
                case "SAVE_STATE" -> ledger.saveInstanceState(activityToken,
                        new SavedActivityState(request.getLong(RuntimeKeys.SAVED_STATE_VERSION, 1L),
                                savedState(request),
                                request.getByteArray(RuntimeKeys.SAVED_STATE_PAYLOAD),
                                request.getByteArray(RuntimeKeys.SAVED_STATE_PERSISTABLE_PAYLOAD)));
                case "CONFIGURATION" -> {
                    ConfigurationDecision decision = ledger.handleConfigurationChange(
                            activityToken, required(request, RuntimeKeys.CONFIGURATION_TOKEN),
                            request.getBoolean(RuntimeKeys.HANDLES_CONFIGURATION, true));
                    out.putString(RuntimeKeys.ACTIVITY_TOKEN, decision.currentActivityToken());
                    out.putString(RuntimeKeys.ACTIVITY_ACTION, decision.action().name());
                }
                // These are real ActivityThread callbacks, but they do not mutate the virtual
                // back stack.  Acknowledge them through the same generation/owner fence so a
                // late callback from a dead process cannot be mistaken for a live Activity.
                case "RESTORED_STATE", "POST_CREATED", "RESTARTED", "USER_LEAVING",
                        "MULTI_WINDOW", "PICTURE_IN_PICTURE" ->
                        out.putBoolean("frameworkCallbackAcknowledged", true);
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
            // containsActivity() and processIdentity() are deliberately separate public ledger
            // reads. A concurrent CLEAR_TOP/DESTROY can remove the record between those reads,
            // so the old implementation converted a valid late Framework callback into an
            // error and then restored a pre-removal rollback snapshot. Never resurrect a record
            // that has already been fenced out; framework-owned lifecycle callbacks are
            // idempotent once their virtual owner is gone.
            if (isFrameworkLifecycleEvent(request, event)
                    && isUnknownActivityToken(failure)) {
                android.util.Log.i("CS_ACTIVITY_EVENT",
                        "stale lifecycle event ignored after race event=" + event
                                + " activityToken=" + activityToken);
                return staleEventResult(activityToken);
            }
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    private static boolean isFrameworkLifecycleEvent(Bundle request, String event) {
        if (!request.getBoolean("frameworkOwnedActivity", false)) return false;
        return "CREATED".equals(event) || "STARTED".equals(event)
                || "RESUMED".equals(event) || "PAUSED".equals(event)
                || "STOPPED".equals(event) || "SAVE_STATE".equals(event)
                || "DESTROYED".equals(event) || "NEW_INTENT".equals(event)
                || "CONFIGURATION".equals(event) || "FINISH_RESULT".equals(event)
                || "RESTORED_STATE".equals(event) || "POST_CREATED".equals(event)
                || "RESTARTED".equals(event) || "USER_LEAVING".equals(event)
                || "MULTI_WINDOW".equals(event) || "PICTURE_IN_PICTURE".equals(event)
                || "FAILED".equals(event);
    }

    private Bundle staleEventResult(String activityToken) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "ACTIVITY_EVENT_APPLIED");
        out.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
        out.putBoolean("staleLifecycleEventIgnored", true);
        out.putInt(RuntimeKeys.ACTIVITY_COUNT, ledger.activityCount());
        out.putInt(RuntimeKeys.TASK_COUNT, ledger.taskCount());
        return out;
    }

    private static boolean isUnknownActivityToken(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.startsWith("Unknown activity token:")) return true;
            cursor = cursor.getCause();
        }
        return false;
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
