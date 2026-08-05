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
import com.warden.controlledsandbox.runtime.protocol.OrderedBroadcastResultExtrasCodec;

import java.util.ArrayList;

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
        ArrayList<BroadcastRoute> routes = new ArrayList<>();
        for (BrokerManifestReceiverRuntime.Route route : manifest.routeImplicit(request, sender)) {
            routes.add(BroadcastRoute.manifest(route));
        }
        boolean external = request.getBoolean("externalBroadcast", false);
        for (DynamicReceiverRegistry.Registration registration : dynamicRoutes(
                request, sender, external)) {
            routes.add(BroadcastRoute.dynamic(registration));
        }
        routes.sort(java.util.Comparator.comparingInt(BroadcastRoute::priority).reversed()
                .thenComparing(BroadcastRoute::label));
        OrderedBroadcastState initialState = OrderedBroadcastState.initial(
                request.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, 0),
                request.getString(RuntimeKeys.BROADCAST_RESULT_DATA, ""),
                OrderedBroadcastResultExtrasCodec.encode(
                        request.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS)));
        ManifestBroadcastDispatcher.DispatchReport report = dispatcher.dispatchRoutes(
                routes, orderedDelivery,
                orderedDelivery && request.getBoolean(RuntimeKeys.BROADCAST_STOP_ON_FAILURE, false),
                initialState, clock,
                request.getLong(RuntimeKeys.BROADCAST_CHAIN_TIMEOUT_MS,
                        ManifestBroadcastDispatcher.DEFAULT_CHAIN_TIMEOUT_MS),
                BroadcastRoute::label,
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
                    Bundle result = route.manifestRoute != null
                            ? deliverManifestRoute(deliveryRequest, sender, route.manifestRoute)
                            : deliverDynamicRoute(deliveryRequest, sender, route.dynamicRegistration);
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
        java.util.List<DynamicReceiverRegistry.Registration> registrations =
                dynamicRoutes(request, sender, external);
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

    private Bundle deliverDynamicRoute(Bundle request, GuestSession sender,
                                       DynamicReceiverRegistry.Registration registration) throws Exception {
        GuestSession target = sessionFinder.find(registration.sessionId(), registration.generation());
        if (!isReady(target)) return failure("DYNAMIC_RECEIVER_SESSION_UNAVAILABLE", registration.id());
        Bundle base = brokerState.prepared(processKey(
                target.packageName(), target.virtualUserId(), target.processName()));
        if (base == null) return failure("DYNAMIC_RECEIVER_PREPARED_SPEC_MISSING", registration.id());
        Bundle call = new Bundle(base);
        call.putAll(request);
        call.putString(ComponentOperations.OPERATION, ComponentOperations.SEND_BROADCAST);
        call.putString(RuntimeKeys.PACKAGE_NAME, target.packageName());
        call.putInt(RuntimeKeys.VIRTUAL_USER_ID, target.virtualUserId());
        call.putString(RuntimeKeys.PROCESS_NAME, target.processName());
        call.putString(RuntimeKeys.COMPONENT_CLASS, registration.receiverClass());
        call.putString(RuntimeKeys.RECEIVER_ID, registration.id());
        call.putBoolean(RuntimeKeys.RECEIVER_MANIFEST, false);
        call.putInt(RuntimeKeys.BROADCAST_PRIORITY, registration.priority());
        call.putString(RuntimeKeys.CALLER_PACKAGE_NAME, sender.packageName());
        call.putInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, sender.virtualUserId());
        call.putString(RuntimeKeys.CALLER_SESSION_ID, sender.sessionId());
        call.putLong(RuntimeKeys.CALLER_GENERATION, sender.generation());
        Bundle result = invokeReceiver(target, registration.receiverClass(), call, request);
        result.putString(RuntimeKeys.SESSION_ID, target.sessionId());
        result.putLong(RuntimeKeys.GENERATION, target.generation());
        result.putInt(RuntimeKeys.PROCESS_SLOT, target.processSlot());
        result.putString(RuntimeKeys.PROCESS_NAME, target.processName());
        result.putString(RuntimeKeys.TARGET_PACKAGE_NAME, target.packageName());
        result.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, target.virtualUserId());
        result.putString(RuntimeKeys.COMPONENT_CLASS, registration.receiverClass());
        result.putString(RuntimeKeys.RECEIVER_ID, registration.id());
        result.putBoolean(RuntimeKeys.RECEIVER_MANIFEST, false);
        result.putInt(RuntimeKeys.BROADCAST_PRIORITY, registration.priority());
        return result;
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
        Bundle result = invokeReceiver(deliveryTarget, receiver.className(), call, request);
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

    private Bundle invokeReceiver(GuestSession deliveryTarget, String receiverClass,
                                  Bundle call, Bundle request) throws Exception {
        boolean orderedDelivery = request.getBoolean(RuntimeKeys.BROADCAST_ORDERED, false);
        OrderedReceiverTokenRegistry.Lease orderedLease = null;
        if (orderedDelivery) {
            lifecycle.purgeExpired();
            orderedLease = ordered.issue(deliveryTarget, receiverClass,
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
        return result;
    }

    private java.util.List<DynamicReceiverRegistry.Registration> dynamicRoutes(
            Bundle request, GuestSession sender, boolean external) {
        java.util.List<DynamicReceiverRegistry.Registration> candidates = dynamic.resolve(
                request, sender.virtualUserId(), external ? "" : sender.sessionId(), external);
        String requiredReceiverPermission = request.getString(
                RuntimeKeys.BROADCAST_REQUIRED_RECEIVER_PERMISSION, "").trim();
        ArrayList<DynamicReceiverRegistry.Registration> allowed = new ArrayList<>();
        for (DynamicReceiverRegistry.Registration registration : candidates) {
            String senderPermission = registration.requiredSenderPermission();
            if (!senderPermission.isEmpty() && !manifest.packageRequestsPermission(
                    sender.packageName(), sender.virtualUserId(), senderPermission)) {
                continue;
            }
            if (!requiredReceiverPermission.isEmpty() && !manifest.packageRequestsPermission(
                    registration.packageName(), registration.virtualUserId(),
                    requiredReceiverPermission)) {
                continue;
            }
            allowed.add(registration);
        }
        return java.util.Collections.unmodifiableList(allowed);
    }

    private static final class BroadcastRoute {
        final BrokerManifestReceiverRuntime.Route manifestRoute;
        final DynamicReceiverRegistry.Registration dynamicRegistration;
        final int priority;
        final String label;

        private BroadcastRoute(BrokerManifestReceiverRuntime.Route manifestRoute,
                               DynamicReceiverRegistry.Registration dynamicRegistration,
                               int priority, String label) {
            this.manifestRoute = manifestRoute;
            this.dynamicRegistration = dynamicRegistration;
            this.priority = priority;
            this.label = label;
        }
        static BroadcastRoute manifest(BrokerManifestReceiverRuntime.Route route) {
            return new BroadcastRoute(route, null, route.priority(),
                    route.receiver().packageName() + "/" + route.receiver().className());
        }
        static BroadcastRoute dynamic(DynamicReceiverRegistry.Registration registration) {
            return new BroadcastRoute(null, registration, registration.priority(),
                    registration.packageName() + "/" + registration.receiverClass() + "#" + registration.id());
        }
        int priority() { return priority; }
        String label() { return label; }
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
            update.resultExtras(OrderedBroadcastResultExtrasCodec.encode(
                    result.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS)));
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
            target.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS,
                    OrderedBroadcastResultExtrasCodec.decode(update.resultExtras()));
        }
        if (update.abortRequested()) target.putBoolean(RuntimeKeys.BROADCAST_ABORT, true);
        if (update.clearAbortRequested()) target.putBoolean(RuntimeKeys.BROADCAST_CLEAR_ABORT, true);
    }
    private static void putOrderedState(Bundle target, OrderedBroadcastState state) {
        target.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, state.resultCode());
        target.putString(RuntimeKeys.BROADCAST_RESULT_DATA, state.resultData());
        target.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS,
                OrderedBroadcastResultExtrasCodec.decode(state.resultExtras()));
        target.putBoolean(RuntimeKeys.BROADCAST_ABORTED, state.aborted());
    }

}
