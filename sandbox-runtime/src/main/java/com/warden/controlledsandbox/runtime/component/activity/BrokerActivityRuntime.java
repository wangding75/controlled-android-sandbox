package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.broker.BrokerStateStore;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.ActivityTaskSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.framework.activity.ActivityIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchCoordinator;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchSpec;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchTransaction;
import com.warden.controlledsandbox.framework.activity.ActivityProcessIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import com.warden.controlledsandbox.framework.activity.ActivityTaskRestoreOutcome;
import com.warden.controlledsandbox.framework.activity.TaskQuerySnapshot;
import com.warden.controlledsandbox.framework.activity.ConfigurationDecision;
import com.warden.controlledsandbox.framework.activity.LaunchAction;
import com.warden.controlledsandbox.framework.activity.LaunchFlags;
import com.warden.controlledsandbox.framework.activity.LaunchMode;
import com.warden.controlledsandbox.framework.activity.LifecycleState;
import com.warden.controlledsandbox.framework.activity.SavedActivityState;
import com.warden.controlledsandbox.framework.routing.OneTimeRouteStore;
import com.warden.controlledsandbox.framework.routing.RouteOwner;
import com.warden.controlledsandbox.framework.routing.RoutePayload;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Broker-owned production adapter for the B2 Activity ledger and one-time route store. */
public final class BrokerActivityRuntime {
    private static final Duration ROUTE_TTL = Duration.ofSeconds(30);
    private final ActivityTaskLedger ledger;
    private final OneTimeRouteStore routeStore;
    private final ActivityLaunchCoordinator coordinator;
    private final BrokerStateStore transport;
    private final ConcurrentMap<String, ActivityLaunchTransaction> pending = new ConcurrentHashMap<>();
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
    }

    public synchronized ActivityTaskRestoreOutcome configureCheckpointStore(Path file) {
        if (checkpointStore != null) throw new IllegalStateException("Activity task checkpoint store already configured");
        if (ledger.taskCount() != 0 || ledger.activityCount() != 0 || !pending.isEmpty()) {
            throw new IllegalStateException("Activity runtime must be idle before checkpoint restore");
        }
        checkpointStore = new ActivityTaskCheckpointStore(file);
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
            checkpointStore.quarantineCorrupt();
            checkpointStatus = "QUARANTINED:" + corruption.getMessage();
            restoreOutcome = new ActivityTaskRestoreOutcome(0, 0, 0, 0);
            return restoreOutcome;
        }
    }

    public synchronized String checkpointStatus() { return checkpointStatus; }
    public synchronized ActivityTaskRestoreOutcome restoreOutcome() { return restoreOutcome; }

    public synchronized Bundle launch(GuestSession session, String component, Bundle prepared, Bundle request) {
        int adopted = ledger.adoptRestoredProcessGeneration(
                session.virtualUserId(), session.packageName(), session.processName(), session.generation());
        ActivityLaunchSpec spec = launchSpec(session, component, request);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(RuntimeKeys.SESSION_ID, session.sessionId());
        metadata.put(RuntimeKeys.COMPONENT_CLASS, component);
        metadata.put(RuntimeKeys.PROCESS_NAME, session.processName());
        ActivityLaunchTransaction transaction = coordinator.launch(
                spec,
                component.getBytes(StandardCharsets.UTF_8),
                metadata,
                ROUTE_TTL);
        String token = transaction.routeToken().value();
        Bundle envelope = new Bundle(prepared);
        if (request != null) envelope.putAll(request);
        envelope.putString(RuntimeKeys.COMPONENT_CLASS, component);
        envelope.putString(RuntimeKeys.ROUTE_TOKEN, token);
        addDecision(envelope, transaction);
        transport.putRoute(token, envelope);
        if (pending.putIfAbsent(token, transaction) != null) {
            transport.removeRoute(token);
            routeStore.revoke(token);
            throw new IllegalStateException("DUPLICATE_ACTIVITY_TRANSACTION");
        }
        if (adopted > 0) envelope.putInt(RuntimeKeys.RESTORED_ACTIVITY_COUNT, adopted);
        persistCheckpoint();
        return new Bundle(envelope);
    }

    public synchronized Bundle consume(String token, GuestSession session) {
        ActivityLaunchTransaction transaction = pending.get(token);
        if (transaction == null) throw new IllegalStateException("ACTIVITY_TRANSACTION_NOT_FOUND");
        RouteOwner expected = owner(session);
        Optional<RoutePayload> payload = coordinator.consumePayload(transaction, expected);
        if (payload.isEmpty()) {
            pending.remove(token, transaction);
            transport.removeRoute(token);
            throw new IllegalStateException("ACTIVITY_ROUTE_EXPIRED_OR_CONSUMED");
        }
        Bundle envelope = transport.consumeRoute(token);
        pending.remove(token, transaction);
        if (envelope == null) throw new IllegalStateException("ACTIVITY_ROUTE_ENVELOPE_MISSING");
        envelope.putString(RuntimeKeys.STATUS, "ROUTE_GRANTED");
        addDecision(envelope, transaction);
        envelope.putLong(RuntimeKeys.ROUTE_EXPIRES_AT, payload.get().expiresAtMillis());
        return envelope;
    }

    public synchronized void launchFailed(String token) {
        ActivityLaunchTransaction transaction = pending.remove(token);
        transport.removeRoute(token);
        routeStore.revoke(token);
        if (transaction == null) return;
        LaunchAction action = transaction.decision().action();
        if (action == LaunchAction.CREATED_ACTIVITY || action == LaunchAction.CREATED_TASK) {
            ledger.finish(transaction.decision().activityToken());
            persistCheckpoint();
        }
    }

    public synchronized Bundle event(GuestSession session, Bundle request) {
        if (request == null) throw new IllegalArgumentException("activity event request is required");
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
            default -> throw new IllegalArgumentException("Unknown activity event: " + event);
        }
        out.putInt(RuntimeKeys.ACTIVITY_COUNT, ledger.activityCount());
        out.putInt(RuntimeKeys.TASK_COUNT, ledger.taskCount());
        persistCheckpoint();
        return out;
    }

    public synchronized void recreate(GuestSession stale, GuestSession current) {
        coordinator.recreateProcessGeneration(stale.virtualUserId(), stale.packageName(), stale.processName(),
                stale.generation(), current.generation());
        purgePending(stale);
        persistCheckpoint();
    }

    public synchronized void processDisconnected(GuestSession stale) {
        routeStore.revokeStaleGenerations(stale.virtualUserId(), stale.packageName(), stale.processName(),
                stale.generation());
        purgePending(stale);
    }

    public synchronized void invalidate(GuestSession stale) {
        coordinator.invalidateProcessGeneration(stale.virtualUserId(), stale.packageName(), stale.processName(),
                stale.generation());
        purgePending(stale);
        persistCheckpoint();
    }

    public synchronized ActivityTaskResult taskOperation(
            GuestSession session,
            ActivityTaskRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        verifySession(request, session);
        boolean changed = false;
        List<ActivityTaskSnapshot> tasks = List.of();
        switch (request.operation()) {
            case ActivityTaskRequest.QUERY_RUNNING -> tasks = projectTasks(ledger.runningTasks(
                    session.virtualUserId(), session.packageName(), request.maxCount()));
            case ActivityTaskRequest.QUERY_RECENT -> tasks = projectTasks(ledger.recentTasks(
                    session.virtualUserId(), session.packageName(), request.maxCount()));
            case ActivityTaskRequest.MOVE_TO_FRONT -> {
                changed = ledger.moveTaskToFront(
                        session.virtualUserId(), session.packageName(), request.taskId());
                persistCheckpoint();
            }
            case ActivityTaskRequest.REMOVE_TASK -> {
                changed = ledger.removeTask(
                        session.virtualUserId(), session.packageName(), request.taskId());
                persistCheckpoint();
            }
            case ActivityTaskRequest.CHECKPOINT_STATUS -> { }
            default -> throw new IllegalArgumentException(
                    "Unknown Activity task operation: " + request.operation());
        }
        return ActivityTaskResult.success(
                RuntimeProtocol.CURRENT,
                request.requestId(),
                request.operation(),
                changed,
                checkpointStatus,
                ledger.taskCount(),
                ledger.activityCount(),
                restoreOutcome.restoredTaskCount(),
                restoreOutcome.restoredActivityCount(),
                restoreOutcome.droppedTransportDeliveryCount(),
                tasks);
    }

    public synchronized int pendingRouteCount() { return pending.size(); }
    public synchronized int taskCount() { return ledger.taskCount(); }
    public synchronized int activityCount() { return ledger.activityCount(); }

    private void persistCheckpoint() {
        if (checkpointStore == null) return;
        checkpointStore.save(ledger.checkpoint());
        checkpointStatus = "PERSISTED";
    }

    private static List<ActivityTaskSnapshot> projectTasks(List<TaskQuerySnapshot> snapshots) {
        return snapshots.stream().map(snapshot -> new ActivityTaskSnapshot(
                snapshot.taskId(),
                snapshot.virtualUserId(),
                snapshot.packageName(),
                snapshot.affinity(),
                snapshot.documentTask(),
                snapshot.active(),
                snapshot.excludedFromRecents(),
                snapshot.retainInRecents(),
                snapshot.activityCount(),
                snapshot.baseComponentName(),
                snapshot.topComponentName(),
                snapshot.lastActiveSequence(),
                snapshot.moveToFrontCount())).toList();
    }

    private static void verifySession(ActivityTaskRequest request, GuestSession session) {
        if (!RuntimeProtocol.isCompatible(request.protocolVersion())) {
            throw new IllegalArgumentException("ACTIVITY_TASK_PROTOCOL_MISMATCH");
        }
        if (!session.sessionId().equals(request.sessionId())
                || session.generation() != request.generation()
                || session.virtualUserId() != request.virtualUserId()
                || !session.packageName().equals(request.packageName())) {
            throw new SecurityException("ACTIVITY_TASK_SESSION_MISMATCH");
        }
    }

    private void purgePending(GuestSession stale) {
        for (Map.Entry<String, ActivityLaunchTransaction> entry : pending.entrySet()) {
            if (entry.getValue().routeOwner().equals(owner(stale)) && pending.remove(entry.getKey(), entry.getValue())) {
                transport.removeRoute(entry.getKey());
                routeStore.revoke(entry.getKey());
            }
        }
    }

    private static ActivityLaunchSpec launchSpec(GuestSession session, String component, Bundle request) {
        Bundle input = request == null ? new Bundle() : request;
        int flags = input.getInt(RuntimeKeys.ACTIVITY_FLAGS, LaunchFlags.NEW_TASK);
        Integer callerTaskId = input.getInt(RuntimeKeys.CALLER_TASK_ID, 0) > 0
                ? input.getInt(RuntimeKeys.CALLER_TASK_ID, 0) : null;
        return new ActivityLaunchSpec(
                new ActivityIdentity(session.virtualUserId(), session.packageName(), component),
                input.getString(RuntimeKeys.TASK_AFFINITY, session.packageName()),
                parseLaunchMode(input.getString(RuntimeKeys.ACTIVITY_LAUNCH_MODE, "STANDARD")),
                flags,
                callerTaskId,
                session.processName(),
                session.generation(),
                input.getString(RuntimeKeys.RESULT_WHO, ""),
                input.getInt(RuntimeKeys.REQUEST_CODE, -1));
    }

    private static LaunchMode parseLaunchMode(String value) {
        try { return LaunchMode.valueOf(value == null ? "STANDARD" : value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Unknown launch mode: " + value, error); }
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
