package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.broker.BrokerStateStore;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchCoordinator;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchSpec;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchTransaction;
import com.warden.controlledsandbox.framework.activity.ActivityProcessIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import com.warden.controlledsandbox.framework.activity.ActivityTaskRestoreOutcome;
import com.warden.controlledsandbox.framework.activity.ConfigurationDecision;
import com.warden.controlledsandbox.framework.activity.LaunchDecision;
import com.warden.controlledsandbox.framework.activity.LaunchAction;
import com.warden.controlledsandbox.framework.activity.LifecycleState;
import com.warden.controlledsandbox.framework.activity.SavedActivityState;
import com.warden.controlledsandbox.framework.routing.OneTimeRouteStore;
import com.warden.controlledsandbox.framework.routing.RouteOwner;
import com.warden.controlledsandbox.framework.routing.RoutePayload;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.warden.controlledsandbox.framework.activity.ActivityRecreation;
import com.warden.controlledsandbox.framework.activity.ProcessRecreationOutcome;

/** Broker-owned production adapter for the B2 Activity ledger and one-time route store. */
public final class BrokerActivityRuntime {
    private static final Duration ROUTE_TTL = Duration.ofSeconds(30);
    private final ActivityTaskLedger ledger;
    private final OneTimeRouteStore routeStore;
    private final ActivityLaunchCoordinator coordinator;
    private final ActivityTaskOperationDispatcher taskOperations;
    private final ActivityResultOperationDispatcher resultOperations;
    private final ActivityCheckpointTransaction checkpointTransactions;
    private final BrokerStateStore transport;
    private final ConcurrentMap<String, ActivityLaunchTransaction> pending = new ConcurrentHashMap<>();
    /** Consumed routes retained only as process-restart candidates until their task is finalized. */
    private final ConcurrentMap<String, ConsumedRoute> consumed = new ConcurrentHashMap<>();
    private ActivityTaskCheckpointStore checkpointStore;
    private String checkpointStatus = "DISABLED";
    private ActivityTaskRestoreOutcome restoreOutcome = new ActivityTaskRestoreOutcome(0, 0, 0, 0);

    public BrokerActivityRuntime(BrokerStateStore transport) {
        this(new ActivityTaskLedger(), new OneTimeRouteStore(), transport);
    }

    public BrokerActivityRuntime(ActivityTaskLedger ledger, OneTimeRouteStore routeStore, BrokerStateStore transport) {
        this.ledger = java.util.Objects.requireNonNull(ledger, "ledger");
        this.routeStore = java.util.Objects.requireNonNull(routeStore, "routeStore");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.coordinator = new ActivityLaunchCoordinator(ledger, routeStore);
        this.taskOperations = new ActivityTaskOperationDispatcher(
                ledger, this::persistCheckpoint, () -> checkpointStatus, () -> restoreOutcome);
        this.resultOperations = new ActivityResultOperationDispatcher(ledger, this::persistCheckpoint);
        this.checkpointTransactions = new ActivityCheckpointTransaction(ledger, this::persistCheckpoint);
    }

    public synchronized ActivityTaskRestoreOutcome configureCheckpointStore(Path file) {
        if (checkpointStore != null) throw new IllegalStateException("Activity task checkpoint store already configured");
        if (ledger.taskCount() != 0 || ledger.activityCount() != 0 || !pending.isEmpty()) {
            throw new IllegalStateException("Activity runtime must be idle before checkpoint restore");
        }
        checkpointStore = new ActivityTaskCheckpointStore(file);
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            Optional<com.warden.controlledsandbox.framework.activity.ActivityTaskCheckpoint> checkpoint =
                    checkpointStore.load();
            if (checkpoint.isEmpty()) {
                checkpointStatus = "EMPTY";
                return restoreOutcome;
            }
            restoreOutcome = ledger.restore(checkpoint.get());
            checkpointStatus = "RESTORED";
            return restoreOutcome;
        } catch (RuntimeException corruption) {
            ledger.restoreRollbackState(before);
            checkpointStore.quarantineCorrupt();
            checkpointStatus = "QUARANTINED:" + corruption.getMessage();
            restoreOutcome = new ActivityTaskRestoreOutcome(0, 0, 0, 0);
            return restoreOutcome;
        }
    }

    public synchronized String checkpointStatus() { return checkpointStatus; }
    public synchronized ActivityTaskRestoreOutcome restoreOutcome() { return restoreOutcome; }

    public synchronized Bundle launch(GuestSession session, String component, Bundle prepared, Bundle request) {
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        String token = "";
        try {
            int adopted = ledger.adoptRestoredProcessGeneration(
                    session.virtualUserId(), session.packageName(), session.packageRevision(),
                    session.processName(), session.generation());
            ActivityLaunchSpec spec = ActivityLaunchSpecFactory.create(session, component, request);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put(RuntimeKeys.SESSION_ID, session.sessionId());
            metadata.put(RuntimeKeys.COMPONENT_CLASS, component);
            metadata.put(RuntimeKeys.PROCESS_NAME, session.processName());
            ActivityLaunchTransaction transaction = coordinator.launch(
                    spec,
                    component.getBytes(StandardCharsets.UTF_8),
                    metadata,
                    ROUTE_TTL);
            token = transaction.routeToken().value();
            Bundle envelope = new Bundle(prepared);
            if (request != null) envelope.putAll(request);
            envelope.putInt(RuntimeKeys.ACTIVITY_FLAGS, spec.flags());
            envelope.putString(RuntimeKeys.DOCUMENT_LAUNCH_MODE, spec.documentLaunchMode().name());
            envelope.putString(RuntimeKeys.DOCUMENT_KEY, spec.documentKey());
            envelope.putString(RuntimeKeys.ACTIVITY_RESULT_KEY, spec.activityResultKey());
            envelope.putString(RuntimeKeys.INTENT_SENDER_TOKEN, spec.intentSenderToken());
            envelope.putString(RuntimeKeys.COMPONENT_CLASS, component);
            envelope.putString(RuntimeKeys.ROUTE_TOKEN, token);
            addDecision(envelope, transaction);
            transport.putRoute(token, envelope);
            if (pending.putIfAbsent(token, transaction) != null) {
                throw new IllegalStateException("DUPLICATE_ACTIVITY_TRANSACTION");
            }
            if (adopted > 0) envelope.putInt(RuntimeKeys.RESTORED_ACTIVITY_COUNT, adopted);
            persistCheckpoint();
            return new Bundle(envelope);
        } catch (RuntimeException failure) {
            if (!token.isEmpty()) {
                pending.remove(token);
                transport.removeRoute(token);
                routeStore.revoke(token);
            }
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    public synchronized Bundle consume(String token, GuestSession session) {
        ActivityLaunchTransaction transaction = pending.get(token);
        if (transaction == null) throw new IllegalStateException("ACTIVITY_TRANSACTION_NOT_FOUND");
        ConsumedRoute alreadyConsumed = consumed.get(token);
        if (alreadyConsumed != null) {
            if (!alreadyConsumed.recoverable) {
                throw new IllegalStateException("ACTIVITY_ROUTE_ALREADY_CONSUMED");
            }
            alreadyConsumed.recoverable = false;
            Bundle replay = new Bundle(alreadyConsumed.envelope);
            replay.putString(RuntimeKeys.STATUS, "ROUTE_GRANTED");
            addDecision(replay, transaction);
            return replay;
        }
        RouteOwner expected = owner(session);
        Optional<RoutePayload> payload = coordinator.consumePayload(transaction, expected);
        if (payload.isEmpty()) {
            pending.remove(token, transaction);
            transport.removeRoute(token);
            throw new IllegalStateException("ACTIVITY_ROUTE_EXPIRED_OR_CONSUMED");
        }
        Bundle envelope = transport.consumeRoute(token);
        if (envelope == null) throw new IllegalStateException("ACTIVITY_ROUTE_ENVELOPE_MISSING");
        envelope.putString(RuntimeKeys.STATUS, "ROUTE_GRANTED");
        addDecision(envelope, transaction);
        envelope.putLong(RuntimeKeys.ROUTE_EXPIRES_AT, payload.get().expiresAtMillis());
        consumed.put(token, new ConsumedRoute(envelope));
        return envelope;
    }

    public synchronized void launchFailed(String token) {
        ConsumedRoute consumedRoute = consumed.get(token);
        if (consumedRoute != null && !consumedRoute.recoverable) return;
        ActivityLaunchTransaction transaction = pending.get(token);
        if (transaction != null) {
            LaunchAction action = transaction.decision().action();
            if (action == LaunchAction.CREATED_ACTIVITY || action == LaunchAction.CREATED_TASK) {
                checkpointTransactions.mutate(
                        () -> ledger.finish(transaction.decision().activityToken()));
            }
            pending.remove(token, transaction);
        }
        transport.removeRoute(token);
        routeStore.revoke(token);
        consumed.remove(token);
    }

    public synchronized Bundle event(GuestSession session, Bundle request) {
        if (request == null) throw new IllegalArgumentException("activity event request is required");
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            String activityToken = required(request, RuntimeKeys.ACTIVITY_TOKEN);
            verifyOwner(activityToken, session);
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
                case "SAVE_STATE" -> {
                    long version = request.getLong(RuntimeKeys.SAVED_STATE_VERSION, 1L);
                    ledger.saveInstanceState(activityToken, new SavedActivityState(version, savedState(request)));
                }
                case "CONFIGURATION" -> {
                    ConfigurationDecision decision = ledger.handleConfigurationChange(
                            activityToken,
                            required(request, RuntimeKeys.CONFIGURATION_TOKEN),
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
                releaseActivityRoute(activityToken);
            }
            out.putInt(RuntimeKeys.ACTIVITY_COUNT, ledger.activityCount());
            out.putInt(RuntimeKeys.TASK_COUNT, ledger.taskCount());
            persistCheckpoint();
            return out;
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    public synchronized void recreate(GuestSession stale, GuestSession current) {
        final ProcessRecreationOutcome[] outcome = new ProcessRecreationOutcome[1];
        checkpointTransactions.mutate(() -> outcome[0] = coordinator.recreateProcessGeneration(
                stale.virtualUserId(), stale.packageName(), stale.processName(),
                stale.generation(), current.generation()));
        rebindTransactions(stale, current, outcome[0].recreations());
    }

    public synchronized void processDisconnected(GuestSession stale) {
        // The framework may recreate a Stub Activity with the original Intent after the Guest
        // process dies.  Keep accepted routes bounded by their existing TTL and mark consumed
        // envelopes replayable only for that exact process recovery.
        for (Map.Entry<String, ConsumedRoute> entry : consumed.entrySet()) {
            ActivityLaunchTransaction transaction = pending.get(entry.getKey());
            if (transaction != null && transaction.routeOwner().equals(owner(stale))) {
                entry.getValue().recoverable = true;
            }
        }
    }

    public synchronized void invalidate(GuestSession stale) {
        checkpointTransactions.mutate(() -> coordinator.invalidateProcessGeneration(
                stale.virtualUserId(), stale.packageName(), stale.processName(), stale.generation()));
        purgePending(stale);
    }

    public synchronized ActivityTaskResult taskOperation(
            GuestSession session,
            ActivityTaskRequest request) {
        return taskOperations.dispatch(session, request);
    }

    public synchronized ActivityResultResult resultOperation(
            GuestSession session,
            ActivityResultRequest request) {
        return resultOperations.dispatch(session, request);
    }

    public synchronized int pendingRouteCount() { return pending.size(); }
    public synchronized int taskCount() { return ledger.taskCount(); }
    public synchronized int activityCount() { return ledger.activityCount(); }

    public synchronized int clearMismatchedRevision(
            int virtualUserId,
            String packageName,
            String retainedRevision) {
        return checkpointTransactions.mutate(() -> ledger.clearPackageRevision(
                virtualUserId, packageName, retainedRevision));
    }

    public synchronized int clearPackageInstance(int virtualUserId, String packageName) {
        return checkpointTransactions.mutate(
                () -> ledger.clearPackageInstance(virtualUserId, packageName));
    }

    private void persistCheckpoint() {
        if (checkpointStore == null) return;
        checkpointStore.save(ledger.checkpoint());
        checkpointStatus = "PERSISTED";
    }

    private void purgePending(GuestSession stale) {
        for (Map.Entry<String, ActivityLaunchTransaction> entry : pending.entrySet()) {
            if (entry.getValue().routeOwner().equals(owner(stale)) && pending.remove(entry.getKey(), entry.getValue())) {
                transport.removeRoute(entry.getKey());
                routeStore.revoke(entry.getKey());
                consumed.remove(entry.getKey());
            }
        }
    }

    /** Returns the original bounded route envelope so Broker recovery can rebuild the Guest. */
    public synchronized Bundle routeForPreparation(String token) {
        Bundle route = transport.route(token);
        if (route != null) return route;
        ConsumedRoute replay = consumed.get(token);
        return replay == null ? null : new Bundle(replay.envelope);
    }

    private void rebindTransactions(
            GuestSession stale,
            GuestSession current,
            java.util.List<ActivityRecreation> recreations) {
        Map<String, String> tokenMap = new LinkedHashMap<>();
        for (ActivityRecreation recreation : recreations) {
            tokenMap.put(recreation.previousActivityToken(), recreation.currentActivityToken());
        }
        RouteOwner staleOwner = owner(stale);
        RouteOwner currentOwner = owner(current);
        for (Map.Entry<String, ActivityLaunchTransaction> entry : pending.entrySet()) {
            ActivityLaunchTransaction transaction = entry.getValue();
            if (!transaction.routeOwner().equals(staleOwner)) continue;
            String currentActivityToken = tokenMap.get(transaction.decision().activityToken());
            if (currentActivityToken == null) {
                launchFailed(entry.getKey());
                continue;
            }
            LaunchDecision decision = new LaunchDecision(
                    transaction.decision().action(), transaction.decision().taskId(),
                    currentActivityToken, transaction.decision().routeToken(),
                    transaction.decision().removedActivityCount(),
                    transaction.decision().createdNewTask());
            ActivityLaunchTransaction rebound = new ActivityLaunchTransaction(
                    decision,
                    new com.warden.controlledsandbox.framework.routing.RouteToken(
                            transaction.routeToken().value(), transaction.routeToken().expiresAtMillis()),
                    currentOwner);
            pending.put(entry.getKey(), rebound);
            transport.rebindRoute(entry.getKey(), current.generation(), currentActivityToken);
            ConsumedRoute consumedRoute = consumed.get(entry.getKey());
            if (consumedRoute != null) {
                consumedRoute.envelope.putLong(RuntimeKeys.GENERATION, current.generation());
                consumedRoute.envelope.putString(RuntimeKeys.ACTIVITY_TOKEN, currentActivityToken);
                consumedRoute.envelope.putString(RuntimeKeys.SESSION_ID, current.sessionId());
            }
        }
    }

    private void releaseActivityRoute(String activityToken) {
        for (Map.Entry<String, ActivityLaunchTransaction> entry : pending.entrySet()) {
            if (!entry.getValue().decision().activityToken().equals(activityToken)) continue;
            pending.remove(entry.getKey(), entry.getValue());
            consumed.remove(entry.getKey());
            transport.removeRoute(entry.getKey());
            routeStore.revoke(entry.getKey());
        }
    }

    private static final class ConsumedRoute {
        private final Bundle envelope;
        private volatile boolean recoverable;

        private ConsumedRoute(Bundle envelope) { this.envelope = new Bundle(envelope); }
    }

    private static void addDecision(Bundle out, ActivityLaunchTransaction transaction) {
        out.putString(RuntimeKeys.ROUTE_TOKEN, transaction.routeToken().value());
        out.putString(RuntimeKeys.ACTIVITY_TOKEN, transaction.decision().activityToken());
        out.putInt(RuntimeKeys.TASK_ID, transaction.decision().taskId());
        out.putString(RuntimeKeys.ACTIVITY_ACTION, transaction.decision().action().name());
        out.putInt(RuntimeKeys.REMOVED_ACTIVITY_COUNT, transaction.decision().removedActivityCount());
    }

    private static RouteOwner owner(GuestSession session) {
        return new RouteOwner(session.virtualUserId(), session.packageName(), session.processName(), session.generation());
    }

    private void verifyOwner(String activityToken, GuestSession session) {
        ActivityProcessIdentity identity = ledger.processIdentity(activityToken);
        if (identity.virtualUserId() != session.virtualUserId()
                || !identity.packageName().equals(session.packageName())
                || !identity.processName().equals(session.processName())
                || identity.processGeneration() != session.generation()) {
            throw new SecurityException("ACTIVITY_OWNER_MISMATCH");
        }
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
