package com.warden.controlledsandbox.runtime.component.activity;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchCoordinator;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchSpec;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchTransaction;
import com.warden.controlledsandbox.framework.activity.ActivityProcessIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import com.warden.controlledsandbox.framework.activity.ActivityRecreation;
import com.warden.controlledsandbox.framework.activity.ActivitySnapshot;
import com.warden.controlledsandbox.framework.activity.LaunchAction;
import com.warden.controlledsandbox.framework.activity.LaunchDecision;
import com.warden.controlledsandbox.framework.activity.LaunchFlags;
import com.warden.controlledsandbox.framework.activity.TaskSnapshot;
import com.warden.controlledsandbox.framework.routing.OneTimeRouteStore;
import com.warden.controlledsandbox.framework.routing.RouteOwner;
import com.warden.controlledsandbox.framework.routing.RoutePayload;
import com.warden.controlledsandbox.framework.routing.RouteToken;
import com.warden.controlledsandbox.runtime.broker.BrokerStateStore;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
    /** Physical Activity identity is bounded and fail-closed; it never wraps or aliases. */
    private final PhysicalActivityIdentityAllocator physicalIdentities =
            new PhysicalActivityIdentityAllocator(PhysicalActivityWindowFamily.WINDOW_SLOT_COUNT);

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
        java.util.Map<String, PhysicalActivityIdentityAllocator.Assignment> physicalBefore =
                physicalIdentities.snapshot();
        String token = "";
        try {
            int adopted = ledger.adoptRestoredProcessGeneration(
                    session.virtualUserId(), session.packageName(), session.packageRevision(),
                    session.processName(), session.generation());
            ActivityLaunchSpec spec = ActivityLaunchSpecFactory.create(
                    session, component, prepared, request);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put(RuntimeKeys.SESSION_ID, session.sessionId());
            metadata.put(RuntimeKeys.COMPONENT_CLASS, component);
            metadata.put(RuntimeKeys.PROCESS_NAME, session.processName());
            byte[] intentPayload = RuntimeIntentWireCodec.routePayload(request);
            if (intentPayload != null) metadata.put("payloadType", "intent-wire");
            java.util.List<TaskSnapshot> virtualTasksBeforeLaunch = ledger.snapshot();
            ActivityLaunchTransaction transaction = coordinator.launch(
                    spec, intentPayload == null ? component.getBytes(StandardCharsets.UTF_8)
                            : intentPayload, metadata, ROUTE_TTL);
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
            int physicalWindow = physicalWindow(session.processSlot(), component, request,
                    virtualTasksBeforeLaunch, transaction);
            envelope.putInt(RuntimeKeys.PHYSICAL_ACTIVITY_WINDOW, physicalWindow);
            envelope.putString(RuntimeKeys.PHYSICAL_ACTIVITY_COMPONENT,
                    physicalComponent(session.processSlot(), component, prepared, physicalWindow));
            attachSavedState(envelope, transaction.decision().activityToken());
            if (intentPayload != null) RuntimeIntentWireCodec.stripRoutePayload(envelope);
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
            physicalIdentities.restore(physicalBefore);
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
        if ("intent-wire".equals(payload.get().metadata().get("payloadType"))) {
            RuntimeIntentWireCodec.attachRoutePayloadDescriptor(envelope, payload.get().bytes());
        }
        // A restored virtual task is not the same thing as a surviving Android Host task.  Only
        // the real route consumer can acknowledge that the new Stub/ActivityThread task exists.
        transactions.mutate(() -> ledger.attachHostTask(transaction.decision().taskId()));
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
                physicalIdentities.release(transaction.decision().activityToken());
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
                physicalIdentities.release(entry.getValue().decision().activityToken());
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
                    transaction.decision().createdNewTask(),
                    transaction.decision().hostTaskRebindRequired());
            if (physicalIdentities.windowFor(transaction.decision().activityToken()) != null) {
                physicalIdentities.rebind(transaction.decision().activityToken(), currentActivityToken);
            }
            ActivityLaunchTransaction rebound = new ActivityLaunchTransaction(
                    decision,
                    new RouteToken(transaction.routeToken().value(), transaction.routeToken().expiresAtMillis()),
                    currentOwner);
            pending.put(entry.getKey(), rebound);
            Bundle recoveryState = savedStateEnvelope(currentActivityToken);
            transport.rebindRoute(entry.getKey(), current.generation(), currentActivityToken,
                    recoveryState);
            ConsumedRoute consumedRoute = consumed.get(entry.getKey());
            if (consumedRoute != null) {
                consumedRoute.envelope.putLong(RuntimeKeys.GENERATION, current.generation());
                consumedRoute.envelope.putString(RuntimeKeys.ACTIVITY_TOKEN, currentActivityToken);
                consumedRoute.envelope.putString(RuntimeKeys.SESSION_ID, current.sessionId());
                if (recoveryState != null) consumedRoute.envelope.putAll(recoveryState);
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
        physicalIdentities.release(activityToken);
    }

    Bundle routeForPreparation(String token) {
        Bundle route = transport.route(token);
        if (route != null) return route;
        ConsumedRoute replay = consumed.get(token);
        return replay == null ? null : new Bundle(replay.envelope);
    }

    int pendingRouteCount() { return pending.size(); }

    ActivityLaunchCoordinator coordinator() { return coordinator; }

    /**
     * Returns a launcher-task reuse candidate only while its existing physical Activity identity
     * is still known to this Broker generation. A restored/detached task must use the normal
     * recovery route so the Host task is rebuilt rather than guessed.
     */
    ActivityTaskLedger.LauncherTaskReuse launcherTaskReuse(
            int virtualUserId,
            String packageName,
            String packageRevision,
            String launcherComponent,
            String taskAffinity) {
        ActivityTaskLedger.LauncherTaskReuse candidate = ledger.findLauncherTaskReuse(
                virtualUserId, packageName, packageRevision, launcherComponent, taskAffinity);
        if (candidate == null
                || physicalIdentities.windowFor(candidate.top().token()) == null) {
            return null;
        }
        return candidate;
    }

    /**
     * Resolves the bounded physical Activity identity for a launch. Reuse keeps the selected
     * record's identity. CLEAR_TOP+STANDARD rebinds the old target's physical identity to the
     * replacement, allowing the Host ActivityStarter to clear the real old record and create the
     * replacement under the same physical component.
     */
    private int physicalWindow(
            int processSlot,
            String component,
            Bundle request,
            java.util.List<TaskSnapshot> virtualTasksBeforeLaunch,
            ActivityLaunchTransaction transaction) {
        String activityToken = transaction.decision().activityToken();
        Integer existing = physicalIdentities.windowFor(activityToken);
        if (existing != null) return existing;
        LaunchAction action = transaction.decision().action();
        if (action == LaunchAction.DELIVERED_NEW_INTENT
                || action == LaunchAction.CLEARED_TOP
                || action == LaunchAction.REORDERED_TO_FRONT) {
            // After Host/Guest process death the virtual task can still name this
            // activity while the in-process physical-window map is empty. Allocate a
            // fresh Stub window instead of failing the relaunch.
            return physicalIdentities.allocate(processSlot, activityToken);
        }
        int flags = request == null ? 0 : request.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0);
        if (action == LaunchAction.CREATED_ACTIVITY
                && LaunchFlags.has(flags, LaunchFlags.CLEAR_TOP)) {
            String replacedToken = removedTargetToken(
                    virtualTasksBeforeLaunch, transaction.decision().taskId(), component);
            if (replacedToken.isEmpty()) {
                throw new IllegalStateException("PHYSICAL_CLEAR_TOP_TARGET_MAPPING_MISSING:" + component);
            }
            physicalIdentities.rebind(replacedToken, activityToken);
            return physicalIdentities.windowFor(activityToken);
        }
        return physicalIdentities.allocate(processSlot, activityToken);
    }

    private String removedTargetToken(
            java.util.List<TaskSnapshot> tasks, int taskId, String component) {
        java.util.List<String> removed = ledger.lastLaunchRemovedActivityTokens();
        for (TaskSnapshot task : tasks) {
            if (task.taskId() != taskId) continue;
            for (ActivitySnapshot activity : task.activities()) {
                if (removed.contains(activity.token())
                        && component.equals(activity.identity().componentName())) {
                    return activity.token();
                }
            }
        }
        return "";
    }

    private String physicalComponent(int processSlot, String component, Bundle prepared, int window) {
        if (prepared == null) throw new IllegalStateException("PACKAGE_STATE_MISSING");
        prepared.setClassLoader(VirtualPackageStateSnapshot.class.getClassLoader());
        VirtualPackageStateSnapshot state = prepared.getParcelable(RuntimeKeys.PACKAGE_STATE);
        // Minimal in-process contract tests do not carry package metadata; production launches
        // always do, and the runtime launch coordinator independently validates the real family.
        if (state == null) {
            return PhysicalActivityWindowFamily.OPAQUE.componentName(processSlot, window);
        }
        return PhysicalActivityWindowFamily.of(component, state).componentName(processSlot, window);
    }


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

    /** Projects only the bounded, opaque saved-state capability into a recovery route. */
    private void attachSavedState(Bundle target, String activityToken) {
        Bundle state = savedStateEnvelope(activityToken);
        if (state != null) target.putAll(state);
    }

    private Bundle savedStateEnvelope(String activityToken) {
        Optional<com.warden.controlledsandbox.framework.activity.SavedActivityState> saved =
                ledger.savedInstanceState(activityToken);
        if (saved.isEmpty()) return null;
        com.warden.controlledsandbox.framework.activity.SavedActivityState state = saved.get();
        Bundle envelope = new Bundle();
        envelope.putLong(RuntimeKeys.SAVED_STATE_VERSION, state.version());
        byte[] payload = state.bundlePayload();
        if (payload.length != 0) envelope.putByteArray(RuntimeKeys.SAVED_STATE_PAYLOAD, payload);
        byte[] persistablePayload = state.persistableBundlePayload();
        if (persistablePayload.length != 0) {
            envelope.putByteArray(RuntimeKeys.SAVED_STATE_PERSISTABLE_PAYLOAD, persistablePayload);
        }
        for (Map.Entry<String, String> entry : state.values().entrySet()) {
            envelope.putString(RuntimeKeys.SAVED_STATE_PREFIX + entry.getKey(), entry.getValue());
        }
        return envelope;
    }

    private void addDecision(Bundle out, ActivityLaunchTransaction transaction) {
        out.putString(RuntimeKeys.ROUTE_TOKEN, transaction.routeToken().value());
        out.putString(RuntimeKeys.ACTIVITY_TOKEN, transaction.decision().activityToken());
        out.putInt(RuntimeKeys.TASK_ID, transaction.decision().taskId());
        out.putString(RuntimeKeys.ACTIVITY_ACTION, transaction.decision().action().name());
        out.putBoolean(RuntimeKeys.CREATED_NEW_TASK, transaction.decision().createdNewTask());
        out.putBoolean(RuntimeKeys.HOST_TASK_REBIND_REQUIRED,
                transaction.decision().hostTaskRebindRequired());
        out.putInt(RuntimeKeys.REMOVED_ACTIVITY_COUNT, transaction.decision().removedActivityCount());
        ArrayList<String> removed = new ArrayList<>(ledger.lastLaunchRemovedActivityTokens());
        if (!removed.isEmpty()) out.putStringArrayList(RuntimeKeys.REMOVED_ACTIVITY_TOKENS, removed);
    }

    private static final class ConsumedRoute {
        private final Bundle envelope;
        private volatile boolean recoverable;

        private ConsumedRoute(Bundle envelope) { this.envelope = new Bundle(envelope); }
    }
}
