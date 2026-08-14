package com.warden.controlledsandbox.runtime.component.activity;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchCoordinator;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchSpec;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchTransaction;
import com.warden.controlledsandbox.framework.activity.ActivityProcessIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import com.warden.controlledsandbox.framework.activity.ActivityRecreation;
import com.warden.controlledsandbox.framework.activity.LaunchAction;
import com.warden.controlledsandbox.framework.activity.LaunchDecision;
import com.warden.controlledsandbox.framework.routing.OneTimeRouteStore;
import com.warden.controlledsandbox.framework.routing.RouteOwner;
import com.warden.controlledsandbox.framework.routing.RoutePayload;
import com.warden.controlledsandbox.framework.routing.RouteToken;
import com.warden.controlledsandbox.runtime.broker.BrokerStateStore;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Owns one-time Activity route delivery, process-generation rebinding and cleanup. */
final class ActivityRuntimeRouteCoordinator {
    private static final Duration ROUTE_TTL = Duration.ofSeconds(30);
    private final ActivityTaskLedger ledger;
    private final OneTimeRouteStore routeStore;
    private final ActivityLaunchCoordinator coordinator;
    private final BrokerStateStore transport;
    private final ActivityCheckpointTransaction transactions;
    private final Runnable persistCheckpoint;
    private final ConcurrentMap<String, ActivityLaunchTransaction> pending = new ConcurrentHashMap<>();
    /** Consumed routes retained only as process-restart candidates until their task is finalized. */
    private final ConcurrentMap<String, ConsumedRoute> consumed = new ConcurrentHashMap<>();

    ActivityRuntimeRouteCoordinator(
            ActivityTaskLedger ledger,
            OneTimeRouteStore routeStore,
            BrokerStateStore transport,
            ActivityCheckpointTransaction transactions,
            Runnable persistCheckpoint) {
        this.ledger = ledger;
        this.routeStore = routeStore;
        this.transport = transport;
        this.transactions = transactions;
        this.persistCheckpoint = persistCheckpoint;
        this.coordinator = new ActivityLaunchCoordinator(ledger, routeStore);
    }

    Bundle launch(GuestSession session, String component, Bundle prepared, Bundle request) {
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
                    spec, component.getBytes(StandardCharsets.UTF_8), metadata, ROUTE_TTL);
            token = transaction.routeToken().value();
            Bundle envelope = new Bundle(prepared);
            if (request != null) envelope.putAll(request);
            // The caller Guest process may launch a component owned by another declared process.
            // Request identity identifies the caller for policy checks, while the route envelope
            // must remain owned by the selected target session and its Stub slot.
            restoreSessionIdentity(envelope, prepared, session);
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
            persistCheckpoint.run();
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

    Bundle consume(String token, GuestSession session) {
        ActivityLaunchTransaction transaction = pending.get(token);
        if (transaction == null) throw new IllegalStateException("ACTIVITY_TRANSACTION_NOT_FOUND");
        ConsumedRoute alreadyConsumed = consumed.get(token);
        if (alreadyConsumed != null) {
            if (!alreadyConsumed.recoverable) throw new IllegalStateException("ACTIVITY_ROUTE_ALREADY_CONSUMED");
            alreadyConsumed.recoverable = false;
            Bundle replay = new Bundle(alreadyConsumed.envelope);
            replay.putString(RuntimeKeys.STATUS, "ROUTE_GRANTED");
            addDecision(replay, transaction);
            return replay;
        }
        Optional<RoutePayload> payload = coordinator.consumePayload(transaction, owner(session));
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

    void launchFailed(String token) {
        ConsumedRoute consumedRoute = consumed.get(token);
        if (consumedRoute != null && !consumedRoute.recoverable) return;
        ActivityLaunchTransaction transaction = pending.get(token);
        if (transaction != null) {
            LaunchAction action = transaction.decision().action();
            if (action == LaunchAction.CREATED_ACTIVITY || action == LaunchAction.CREATED_TASK) {
                transactions.mutate(() -> ledger.finish(transaction.decision().activityToken()));
            }
            pending.remove(token, transaction);
        }
        transport.removeRoute(token);
        routeStore.revoke(token);
        consumed.remove(token);
    }

    void processDisconnected(GuestSession stale) {
        for (Map.Entry<String, ConsumedRoute> entry : consumed.entrySet()) {
            ActivityLaunchTransaction transaction = pending.get(entry.getKey());
            if (transaction != null && transaction.routeOwner().equals(owner(stale))) {
                entry.getValue().recoverable = true;
            }
        }
    }

    void purgePending(GuestSession stale) {
        for (Map.Entry<String, ActivityLaunchTransaction> entry : pending.entrySet()) {
            if (entry.getValue().routeOwner().equals(owner(stale))
                    && pending.remove(entry.getKey(), entry.getValue())) {
                transport.removeRoute(entry.getKey());
                routeStore.revoke(entry.getKey());
                consumed.remove(entry.getKey());
            }
        }
    }

    void rebindTransactions(GuestSession stale, GuestSession current,
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
                    new RouteToken(transaction.routeToken().value(), transaction.routeToken().expiresAtMillis()),
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

    void releaseActivityRoute(String activityToken) {
        for (Map.Entry<String, ActivityLaunchTransaction> entry : pending.entrySet()) {
            if (!entry.getValue().decision().activityToken().equals(activityToken)) continue;
            pending.remove(entry.getKey(), entry.getValue());
            consumed.remove(entry.getKey());
            transport.removeRoute(entry.getKey());
            routeStore.revoke(entry.getKey());
        }
    }

    Bundle routeForPreparation(String token) {
        Bundle route = transport.route(token);
        if (route != null) return route;
        ConsumedRoute replay = consumed.get(token);
        return replay == null ? null : new Bundle(replay.envelope);
    }

    int pendingRouteCount() { return pending.size(); }

    ActivityLaunchCoordinator coordinator() { return coordinator; }

    void verifyOwner(String activityToken, GuestSession session) {
        ActivityProcessIdentity identity = ledger.processIdentity(activityToken);
        if (identity.virtualUserId() != session.virtualUserId()
                || !identity.packageName().equals(session.packageName())
                || !identity.processName().equals(session.processName())
                || identity.processGeneration() != session.generation()) {
            throw new SecurityException("ACTIVITY_OWNER_MISMATCH");
        }
    }

    static RouteOwner owner(GuestSession session) {
        return new RouteOwner(session.virtualUserId(), session.packageName(),
                session.processName(), session.generation());
    }

    private static void restoreSessionIdentity(Bundle target, Bundle prepared, GuestSession session) {
        copyInt(target, prepared, RuntimeKeys.PROTOCOL);
        target.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        target.putLong(RuntimeKeys.GENERATION, session.generation());
        copyString(target, prepared, RuntimeKeys.PACKAGE_NAME);
        copyInt(target, prepared, RuntimeKeys.VIRTUAL_USER_ID);
        copyInt(target, prepared, RuntimeKeys.VIRTUAL_UID);
        target.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        target.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        copyString(target, prepared, RuntimeKeys.APK_PATH);
        copyString(target, prepared, RuntimeKeys.APK_SHA256);
        copyString(target, prepared, RuntimeKeys.BASE_APK_SHA256);
        copyLong(target, prepared, RuntimeKeys.APK_VERSION_CODE);
        copyString(target, prepared, RuntimeKeys.PACKAGE_REVISION);
        copyString(target, prepared, RuntimeKeys.NATIVE_LIBRARY_DIR);
        copyString(target, prepared, RuntimeKeys.NATIVE_ABI);
        copyString(target, prepared, RuntimeKeys.NATIVE_GUEST_TRUST);
        copyString(target, prepared, RuntimeKeys.NATIVE_EXECUTION_MODE);
        copyString(target, prepared, RuntimeKeys.APPLICATION_CLASS);
        copyString(target, prepared, RuntimeKeys.DATA_ROOT);
        if (prepared.containsKey(RuntimeKeys.PERMISSIONS)) {
            target.putStringArrayList(RuntimeKeys.PERMISSIONS,
                    prepared.getStringArrayList(RuntimeKeys.PERMISSIONS));
        }
        if (prepared.containsKey(RuntimeKeys.PACKAGE_STATE)) {
            target.putParcelable(RuntimeKeys.PACKAGE_STATE,
                    prepared.getParcelable(RuntimeKeys.PACKAGE_STATE));
        }
        if (prepared.containsKey(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER)) {
            target.putBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER,
                    prepared.getBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER));
        }
        if (prepared.containsKey(RuntimeKeys.RUNTIME_BROKER_BINDER)) {
            target.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER,
                    prepared.getBinder(RuntimeKeys.RUNTIME_BROKER_BINDER));
        }
    }

    private static void copyString(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) target.putString(key, source.getString(key, ""));
    }

    private static void copyInt(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) target.putInt(key, source.getInt(key));
    }

    private static void copyLong(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) target.putLong(key, source.getLong(key));
    }

    private static void addDecision(Bundle out, ActivityLaunchTransaction transaction) {
        out.putString(RuntimeKeys.ROUTE_TOKEN, transaction.routeToken().value());
        out.putString(RuntimeKeys.ACTIVITY_TOKEN, transaction.decision().activityToken());
        out.putInt(RuntimeKeys.TASK_ID, transaction.decision().taskId());
        out.putString(RuntimeKeys.ACTIVITY_ACTION, transaction.decision().action().name());
        out.putInt(RuntimeKeys.REMOVED_ACTIVITY_COUNT, transaction.decision().removedActivityCount());
    }

    private static final class ConsumedRoute {
        private final Bundle envelope;
        private volatile boolean recoverable;

        private ConsumedRoute(Bundle envelope) { this.envelope = new Bundle(envelope); }
    }
}
