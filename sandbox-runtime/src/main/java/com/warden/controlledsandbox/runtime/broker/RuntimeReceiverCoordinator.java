package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import android.os.IBinder;

import com.warden.controlledsandbox.contract.IOrderedReceiverCompletion;
import com.warden.controlledsandbox.domain.component.receiver.DynamicReceiverRegistry;
import com.warden.controlledsandbox.domain.component.receiver.ManifestReceiverRegistry;
import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.component.receiver.BroadcastPayloadEstimator;
import com.warden.controlledsandbox.runtime.component.receiver.BrokerManifestReceiverRuntime;
import com.warden.controlledsandbox.runtime.component.receiver.BrokerOrderedReceiverRuntime;
import com.warden.controlledsandbox.runtime.component.receiver.BrokerReceiverRuntime;
import com.warden.controlledsandbox.runtime.component.receiver.ManifestBroadcastDispatcher;
import com.warden.controlledsandbox.runtime.component.receiver.OrderedReceiverTokenRegistry;
import com.warden.controlledsandbox.runtime.component.receiver.ReceiverLifecycleCoordinator;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broker-owned Receiver authority.
 *
 * <p>This coordinator owns dynamic registrations, Manifest routing, ordered completion tokens,
 * and Receiver lifecycle cleanup. RuntimeBrokerService only supplies Session allocation and the
 * concrete Guest Binder invocation.</p>
 */
public final class RuntimeReceiverCoordinator {
    @FunctionalInterface public interface GuestPreparer { Bundle prepare(Bundle request); }
    @FunctionalInterface public interface SessionFinder { GuestSession find(String sessionId, long generation); }
    @FunctionalInterface public interface GuestInvoker { Bundle invoke(int processSlot, Bundle request) throws Exception; }

    private final SessionRegistry sessions;
    private final BrokerStateStore brokerState;
    private final GuestPreparer guestPreparer;
    private final SessionFinder sessionFinder;
    private final GuestInvoker guestInvoker;
    private final Clock clock;
    private final BrokerReceiverRuntime dynamic = new BrokerReceiverRuntime();
    private final BrokerManifestReceiverRuntime manifest = new BrokerManifestReceiverRuntime();
    private final ManifestBroadcastDispatcher dispatcher = new ManifestBroadcastDispatcher();
    private final BrokerOrderedReceiverRuntime ordered;
    private final ReceiverLifecycleCoordinator lifecycle;
    private final IOrderedReceiverCompletion.Stub completion = new IOrderedReceiverCompletion.Stub() {
        @Override public Bundle complete(Bundle result) {
            CallerGuard.requireSameApplication();
            return ordered.complete(result);
        }
    };

    public RuntimeReceiverCoordinator(SessionRegistry sessions, BrokerStateStore brokerState,
                                      Clock clock, TokenGenerator tokens,
                                      GuestPreparer guestPreparer, SessionFinder sessionFinder,
                                      GuestInvoker guestInvoker) {
        if (sessions == null || brokerState == null || clock == null || tokens == null
                || guestPreparer == null || sessionFinder == null || guestInvoker == null) {
            throw new IllegalArgumentException("Receiver coordinator dependencies are required");
        }
        this.sessions = sessions;
        this.brokerState = brokerState;
        this.guestPreparer = guestPreparer;
        this.sessionFinder = sessionFinder;
        this.guestInvoker = guestInvoker;
        this.clock = clock;
        this.ordered = new BrokerOrderedReceiverRuntime(clock, tokens);
        this.lifecycle = new ReceiverLifecycleCoordinator(dynamic, manifest, ordered);
    }

    public ReceiverLifecycleCoordinator lifecycle() { return lifecycle; }
    public void indexPackage(Bundle input) throws Exception { manifest.indexPackage(input); }
    public int bindSession(GuestSession session) { return lifecycle.bindSession(session); }
    public ReceiverLifecycleCoordinator.CleanupResult stopSession(GuestSession session, String reason) {
        return lifecycle.stopSession(session, reason);
    }
    public ReceiverLifecycleCoordinator.RecoveryResult recoverSession(GuestSession stale, GuestSession current) {
        return lifecycle.recoverSession(stale, current);
    }
    public ReceiverLifecycleCoordinator.CleanupResult disconnectSession(GuestSession session, String reason) {
        return lifecycle.disconnectSession(session, reason);
    }
    public ReceiverLifecycleCoordinator.CleanupResult invalidateInstance(String packageName, int userId,
                                                                          String reason) {
        return lifecycle.invalidateInstance(packageName, userId, reason);
    }
    public ReceiverLifecycleCoordinator.CleanupResult invalidateAll(String reason) {
        return lifecycle.invalidateAll(reason);
    }
    public int purgeExpired() { return lifecycle.purgeExpired(); }
    public BrokerReceiverRuntime.Reservation reserveRegistration(Bundle request, GuestSession session) {
        return dynamic.reserveRegistration(request, session);
    }
    public void requireOwnedRegistration(Bundle request, GuestSession session) {
        dynamic.requireOwnedRegistration(request, session);
    }
    public void rollbackRegistration(BrokerReceiverRuntime.Reservation reservation) {
        dynamic.rollbackRegistration(reservation);
    }
    public void commitUnregister(Bundle request, GuestSession session) {
        dynamic.commitUnregister(request, session);
    }

    public Bundle dispatchManifestBroadcast(Bundle request, GuestSession sender) throws Exception {
        return deliverManifestRoute(request, sender, manifest.routeExplicit(request, sender));
    }

    public Bundle dispatchImplicitManifestBroadcast(Bundle request, GuestSession sender,
                                                    boolean orderedDelivery) throws Exception {
        int payloadBytes = BroadcastPayloadEstimator.requireWithinLimit(request);
        java.util.List<BrokerManifestReceiverRuntime.Route> routes = manifest.routeImplicit(request, sender);
        OrderedBroadcastState initialState = OrderedBroadcastState.initial(
                request.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, 0),
                request.getString(RuntimeKeys.BROADCAST_RESULT_DATA, ""),
                stringMap(request.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS)));
        ManifestBroadcastDispatcher.DispatchReport report = dispatcher.dispatch(
                routes, orderedDelivery,
                orderedDelivery && request.getBoolean(RuntimeKeys.BROADCAST_STOP_ON_FAILURE, false),
                initialState, clock,
                request.getLong(RuntimeKeys.BROADCAST_CHAIN_TIMEOUT_MS,
                        ManifestBroadcastDispatcher.DEFAULT_CHAIN_TIMEOUT_MS),
                (route, currentState, remainingChainBudgetMs) -> {
                    Bundle deliveryRequest = new Bundle(request);
                    deliveryRequest.putString(ComponentOperations.OPERATION, ComponentOperations.SEND_BROADCAST);
                    deliveryRequest.putBoolean(RuntimeKeys.BROADCAST_ORDERED, orderedDelivery);
                    deliveryRequest.putInt(RuntimeKeys.BROADCAST_PRIORITY, route.priority());
                    long configuredReceiverTimeoutMs = request.getLong(
                            RuntimeKeys.BROADCAST_RECEIVER_TIMEOUT_MS,
                            OrderedReceiverTokenRegistry.DEFAULT_TIMEOUT_MS);
                    if (configuredReceiverTimeoutMs <= 0) {
                        configuredReceiverTimeoutMs = OrderedReceiverTokenRegistry.DEFAULT_TIMEOUT_MS;
                    }
                    deliveryRequest.putLong(RuntimeKeys.BROADCAST_RECEIVER_TIMEOUT_MS,
                            Math.max(1L, Math.min(configuredReceiverTimeoutMs,
                                    remainingChainBudgetMs)));
                    if (orderedDelivery) putOrderedState(deliveryRequest, currentState);
                    Bundle result = deliverManifestRoute(deliveryRequest, sender, route);
                    String deliveryStatus = result.getString(RuntimeKeys.STATUS, "");
                    if (!"BROADCAST_DELIVERED".equals(deliveryStatus)) {
                        String reason = result.getString(RuntimeKeys.ERROR_TYPE, deliveryStatus);
                        String orderedState = result.getString(RuntimeKeys.ORDERED_RECEIVER_STATE, "");
                        String normalized = reason == null || reason.trim().isEmpty()
                                ? "DELIVERY_NOT_COMPLETED" : reason;
                        return "TIMED_OUT".equals(orderedState) || normalized.contains("TIMEOUT")
                                ? ManifestBroadcastDispatcher.DeliveryOutcome.timeout(normalized)
                                : ManifestBroadcastDispatcher.DeliveryOutcome.failure(normalized);
                    }
                    return ManifestBroadcastDispatcher.DeliveryOutcome.success(
                            orderedDelivery ? resultUpdate(result) : null);
                });
        Bundle out = new Bundle();
        String status;
        if (report.matchedCount() == 0) status = "BROADCAST_NO_RECEIVERS";
        else if (orderedDelivery && report.finalState().aborted()
                && report.processedCount() < report.matchedCount()) status = "ORDERED_BROADCAST_ABORTED";
        else if (report.failedCount() == 0) {
            status = orderedDelivery ? "ORDERED_BROADCAST_DELIVERED" : "BROADCAST_DELIVERED";
        } else if (report.deliveredCount() == 0) status = "BROADCAST_FAILED";
        else status = "BROADCAST_PARTIAL";
        out.putString(RuntimeKeys.STATUS, status);
        out.putString(ComponentOperations.ACTION, required(request, ComponentOperations.ACTION));
        out.putBoolean(RuntimeKeys.BROADCAST_ORDERED, orderedDelivery);
        out.putInt(RuntimeKeys.BROADCAST_MATCHED_COUNT, report.matchedCount());
        out.putInt(RuntimeKeys.BROADCAST_DELIVERED_COUNT, report.deliveredCount());
        out.putInt(RuntimeKeys.BROADCAST_FAILED_COUNT, report.failedCount());
        out.putInt(RuntimeKeys.BROADCAST_SKIPPED_COUNT, report.skippedCount());
        out.putInt(RuntimeKeys.BROADCAST_TIMED_OUT_COUNT, report.timedOutCount());
        out.putLong(RuntimeKeys.BROADCAST_CHAIN_DEADLINE_MS, report.deadlineMs());
        out.putString(RuntimeKeys.BROADCAST_TERMINAL_REASON, report.terminalReason());
        out.putString(RuntimeKeys.BROADCAST_ABORT_SOURCE, report.abortSource());
        out.putInt(RuntimeKeys.BROADCAST_PAYLOAD_BYTES, payloadBytes);
        out.putStringArrayList(RuntimeKeys.BROADCAST_DELIVERY_FAILURES, new ArrayList<>(report.failures()));
        if (orderedDelivery) putOrderedState(out, report.finalState());
        out.putBoolean(RuntimeKeys.BROADCAST_ABORTED,
                orderedDelivery && report.finalState().aborted());
        return out;
    }

    public Bundle dispatchDynamicBroadcast(Bundle request, GuestSession sender) {
        String action = required(request, ComponentOperations.ACTION);
        boolean external = request.getBoolean("externalBroadcast", false);
        java.util.List<DynamicReceiverRegistry.Registration> registrations = dynamic.resolve(
                action, sender.virtualUserId(), external ? "" : sender.sessionId(), external);
        int delivered = 0;
        int failed = 0;
        ArrayList<String> failures = new ArrayList<>();
        for (DynamicReceiverRegistry.Registration registration : registrations) {
            GuestSession target = sessionFinder.find(registration.sessionId(), registration.generation());
            if (!isReady(target)) {
                failed++;
                failures.add(registration.id() + ":SESSION_UNAVAILABLE");
                continue;
            }
            Bundle base = brokerState.prepared(processKey(
                    target.packageName(), target.virtualUserId(), target.processName()));
            if (base == null) {
                failed++;
                failures.add(registration.id() + ":PREPARED_SPEC_MISSING");
                continue;
            }
            Bundle call = new Bundle(base);
            call.putAll(request);
            call.putString(RuntimeKeys.COMPONENT_CLASS, registration.receiverClass());
            call.putString(RuntimeKeys.RECEIVER_ID, registration.id());
            try {
                Bundle result = guestInvoker.invoke(target.processSlot(), call);
                if ("FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                    failed++;
                    failures.add(registration.id() + ":" + result.getString(RuntimeKeys.ERROR_TYPE, "FAILED"));
                } else delivered++;
            } catch (Exception error) {
                failed++;
                failures.add(registration.id() + ":" + error.getClass().getSimpleName());
            }
        }
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, failed == 0 ? "BROADCAST_DELIVERED" : "BROADCAST_PARTIAL");
        out.putString(ComponentOperations.ACTION, action);
        out.putInt("deliveredCount", delivered);
        out.putInt("failedCount", failed);
        out.putStringArrayList("deliveryFailures", failures);
        return out;
    }

    private Bundle deliverManifestRoute(Bundle request, GuestSession sender,
                                        BrokerManifestReceiverRuntime.Route route) throws Exception {
        ManifestReceiverRegistry.Receiver receiver = route.receiver();
        GuestSession target = null;
        if (route.resolution().binding().isPresent()) {
            ManifestReceiverRegistry.SessionBinding binding = route.resolution().binding().get();
            target = sessionFinder.find(binding.sessionId(), binding.generation());
            if (!isReady(target)) target = null;
        }
        boolean processStarted = false;
        if (target == null) {
            GuestSession current = sessions.get(receiver.packageName(), route.virtualUserId(), receiver.processName());
            if (isReady(current)) target = current;
        }
        if (target == null) {
            Bundle activation = manifest.activationRequest(route);
            Bundle prepared = guestPreparer.prepare(activation);
            if (!isPrepared(prepared)) return prepared;
            target = sessions.get(receiver.packageName(), route.virtualUserId(), receiver.processName());
            if (!isReady(target)) throw new IllegalStateException("MANIFEST_RECEIVER_TARGET_SESSION_NOT_READY");
            processStarted = true;
        }
        lifecycle.bindSession(target);
        Bundle base = brokerState.prepared(processKey(
                target.packageName(), target.virtualUserId(), target.processName()));
        if (base == null) throw new IllegalStateException("MANIFEST_RECEIVER_PREPARED_SPEC_MISSING");
        Bundle call = new Bundle(base);
        call.putAll(request);
        call.putString(ComponentOperations.OPERATION, ComponentOperations.SEND_BROADCAST);
        call.putString(RuntimeKeys.PACKAGE_NAME, target.packageName());
        call.putInt(RuntimeKeys.VIRTUAL_USER_ID, target.virtualUserId());
        call.putString(RuntimeKeys.PROCESS_NAME, target.processName());
        call.putString(RuntimeKeys.COMPONENT_CLASS, receiver.className());
        call.putBoolean(RuntimeKeys.RECEIVER_MANIFEST, true);
        call.putString(RuntimeKeys.RECEIVER_PERMISSION, receiver.permission());
        call.putInt(RuntimeKeys.BROADCAST_PRIORITY, route.priority());
        call.putString(RuntimeKeys.CALLER_PACKAGE_NAME, sender.packageName());
        call.putInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, sender.virtualUserId());
        call.putString(RuntimeKeys.CALLER_SESSION_ID, sender.sessionId());
        call.putLong(RuntimeKeys.CALLER_GENERATION, sender.generation());
        final GuestSession deliveryTarget = target;
        boolean orderedDelivery = request.getBoolean(RuntimeKeys.BROADCAST_ORDERED, false);
        OrderedReceiverTokenRegistry.Lease orderedLease = null;
        if (orderedDelivery) {
            lifecycle.purgeExpired();
            orderedLease = ordered.issue(deliveryTarget, receiver.className(),
                    request.getLong(RuntimeKeys.BROADCAST_RECEIVER_TIMEOUT_MS,
                            OrderedReceiverTokenRegistry.DEFAULT_TIMEOUT_MS));
            call.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, orderedLease.token());
            call.putLong(RuntimeKeys.ORDERED_RECEIVER_DEADLINE_MS, orderedLease.deadlineMs());
            call.putBinder(RuntimeKeys.ORDERED_RECEIVER_COMPLETION_BINDER, completion.asBinder());
        }
        Bundle result;
        try {
            result = guestInvoker.invoke(deliveryTarget.processSlot(), call);
        } catch (Throwable error) {
            try {
                if (orderedLease != null) {
                    ordered.cancel(orderedLease,
                            "ORDERED_RECEIVER_GUEST_CALL_FAILED:" + error.getClass().getSimpleName());
                }
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException(error);
        }
        if (orderedLease != null) {
            String guestStatus = result.getString(RuntimeKeys.STATUS, "");
            boolean completionRejected = result.keySet().contains(RuntimeKeys.ORDERED_RECEIVER_ACCEPTED)
                    && !result.getBoolean(RuntimeKeys.ORDERED_RECEIVER_ACCEPTED, false);
            if ("FAILED".equals(guestStatus) || completionRejected) {
                String reason = "FAILED".equals(guestStatus)
                        ? result.getString(RuntimeKeys.ERROR_TYPE, "FAILED") : guestStatus;
                ordered.cancel(orderedLease, "ORDERED_RECEIVER_GUEST_FAILED:" + reason);
                if (completionRejected && !"FAILED".equals(guestStatus)) {
                    result = failure("ORDERED_RECEIVER_COMPLETION_REJECTED", reason);
                }
            } else {
                OrderedReceiverTokenRegistry.AwaitResult completionResult;
                try {
                    completionResult = ordered.await(orderedLease);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    ordered.cancel(orderedLease, "ORDERED_RECEIVER_WAIT_INTERRUPTED");
                    throw interrupted;
                }
                if (completionResult.completed()) {
                    result = new Bundle();
                    result.putString(RuntimeKeys.STATUS, "BROADCAST_DELIVERED");
                    putResultUpdate(result, completionResult.update());
                } else result = failure(completionResult.reason(), completionResult.state().name());
                result.putString(RuntimeKeys.ORDERED_RECEIVER_STATE, completionResult.state().name());
            }
            result.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, orderedLease.token());
            result.putLong(RuntimeKeys.ORDERED_RECEIVER_DEADLINE_MS, orderedLease.deadlineMs());
        }
        result.putString(RuntimeKeys.SESSION_ID, deliveryTarget.sessionId());
        result.putLong(RuntimeKeys.GENERATION, deliveryTarget.generation());
        result.putInt(RuntimeKeys.PROCESS_SLOT, deliveryTarget.processSlot());
        result.putString(RuntimeKeys.PROCESS_NAME, deliveryTarget.processName());
        result.putString(RuntimeKeys.TARGET_PACKAGE_NAME, deliveryTarget.packageName());
        result.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, deliveryTarget.virtualUserId());
        result.putString(RuntimeKeys.COMPONENT_CLASS, receiver.className());
        result.putBoolean(RuntimeKeys.RECEIVER_MANIFEST, true);
        result.putBoolean(RuntimeKeys.RECEIVER_PROCESS_STARTED, processStarted);
        result.putInt(RuntimeKeys.BROADCAST_PRIORITY, route.priority());
        result.putString(RuntimeKeys.RECEIVER_ACTIVATION_KEY,
                "u" + deliveryTarget.virtualUserId() + ":" + deliveryTarget.packageName()
                        + "#" + deliveryTarget.processName());
        return result;
    }

    private static boolean isReady(GuestSession session) {
        return session != null && (session.state() == SessionState.READY || session.state() == SessionState.ACTIVE);
    }
    private static boolean isPrepared(Bundle bundle) {
        String status = bundle == null ? "" : bundle.getString(RuntimeKeys.STATUS, "");
        return "PREPARED".equals(status) || "ALREADY_PREPARED".equals(status)
                || "PREPARED_DEGRADED".equals(status) || "ALREADY_PREPARED_DEGRADED".equals(status);
    }
    private static String required(Bundle bundle, String key) {
        String value = bundle == null ? "" : bundle.getString(key, "");
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }
    private static String processKey(String packageName, int userId, String processName) {
        return RuntimeBrokerService.ownerKey(packageName, userId) + ":" + processName;
    }
    private static Bundle failure(String type, String message) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "FAILED");
        out.putString(RuntimeKeys.ERROR_TYPE, type == null ? "FAILED" : type);
        out.putString(RuntimeKeys.ERROR_MESSAGE, message == null ? "" : message);
        return out;
    }
    private static OrderedBroadcastState.ResultUpdate resultUpdate(Bundle result) {
        OrderedBroadcastState.ResultUpdate update = new OrderedBroadcastState.ResultUpdate();
        java.util.Set<String> keys = result.keySet();
        if (keys.contains(RuntimeKeys.BROADCAST_RESULT_CODE)) {
            update.resultCode(result.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, 0));
        }
        if (keys.contains(RuntimeKeys.BROADCAST_RESULT_DATA)) {
            update.resultData(result.getString(RuntimeKeys.BROADCAST_RESULT_DATA, ""));
        }
        if (keys.contains(RuntimeKeys.BROADCAST_RESULT_EXTRAS)) {
            update.resultExtras(stringMap(result.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS)));
        }
        if (result.getBoolean(RuntimeKeys.BROADCAST_ABORT, false)) update.abort();
        if (result.getBoolean(RuntimeKeys.BROADCAST_CLEAR_ABORT, false)) update.clearAbort();
        return update;
    }
    private static void putResultUpdate(Bundle target, OrderedBroadcastState.ResultUpdate update) {
        if (target == null || update == null) return;
        if (update.hasResultCode()) target.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, update.resultCode());
        if (update.hasResultData()) target.putString(RuntimeKeys.BROADCAST_RESULT_DATA, update.resultData());
        if (update.hasResultExtras()) {
            Bundle extras = new Bundle();
            for (Map.Entry<String, String> entry : update.resultExtras().entrySet()) {
                extras.putString(entry.getKey(), entry.getValue());
            }
            target.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS, extras);
        }
        if (update.abortRequested()) target.putBoolean(RuntimeKeys.BROADCAST_ABORT, true);
        if (update.clearAbortRequested()) target.putBoolean(RuntimeKeys.BROADCAST_CLEAR_ABORT, true);
    }
    private static void putOrderedState(Bundle target, OrderedBroadcastState state) {
        target.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, state.resultCode());
        target.putString(RuntimeKeys.BROADCAST_RESULT_DATA, state.resultData());
        Bundle extras = new Bundle();
        for (Map.Entry<String, String> entry : state.resultExtras().entrySet()) {
            extras.putString(entry.getKey(), entry.getValue());
        }
        target.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS, extras);
        target.putBoolean(RuntimeKeys.BROADCAST_ABORTED, state.aborted());
    }
    private static Map<String, String> stringMap(Bundle bundle) {
        if (bundle == null || bundle.keySet().isEmpty()) return java.util.Collections.emptyMap();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (!(value instanceof String)) throw new IllegalArgumentException("BROADCAST_RESULT_EXTRAS_STRING_ONLY");
            result.put(key, (String) value);
        }
        return result;
    }
}
