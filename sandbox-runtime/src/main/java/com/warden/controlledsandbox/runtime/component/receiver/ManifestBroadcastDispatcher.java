package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import com.warden.controlledsandbox.domain.port.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-Java ordered/unordered manifest broadcast execution policy. */
public final class ManifestBroadcastDispatcher {
    public static final long DEFAULT_CHAIN_TIMEOUT_MS = 60_000L;
    public static final long MAX_CHAIN_TIMEOUT_MS = 120_000L;

    public DispatchReport dispatch(List<BrokerManifestReceiverRuntime.Route> routes,
                                   boolean ordered, boolean stopOnFailure,
                                   OrderedBroadcastState initialState,
                                   Delivery delivery) {
        return dispatch(routes, ordered, stopOnFailure, initialState,
                System::currentTimeMillis, DEFAULT_CHAIN_TIMEOUT_MS, delivery);
    }

    public DispatchReport dispatch(List<BrokerManifestReceiverRuntime.Route> routes,
                                   boolean ordered, boolean stopOnFailure,
                                   OrderedBroadcastState initialState,
                                   Clock clock, long requestedChainTimeoutMs,
                                   Delivery delivery) {
        return dispatchRoutes(routes, ordered, stopOnFailure, initialState, clock,
                requestedChainTimeoutMs,
                route -> route.receiver().packageName() + "/" + route.receiver().className(),
                delivery::deliver);
    }

    public <T> DispatchReport dispatchRoutes(List<T> routes, boolean ordered, boolean stopOnFailure,
                                   OrderedBroadcastState initialState, Clock clock,
                                   long requestedChainTimeoutMs, RouteLabel<T> label,
                                   RouteDelivery<T> delivery) {
        if (routes == null || initialState == null || clock == null || label == null || delivery == null) {
            throw new IllegalArgumentException("routes, initialState, clock, label and delivery are required");
        }
        long startedAtMs = clock.nowMillis();
        if (startedAtMs < 0) throw new IllegalArgumentException("clock returned negative time");
        long deadlineMs = safeAdd(startedAtMs, normalizeChainTimeout(requestedChainTimeoutMs));
        OrderedBroadcastState state = initialState;
        int delivered = 0;
        int failed = 0;
        int timedOut = 0;
        int processed = 0;
        String terminalReason = "COMPLETED";
        String abortSource = "";
        ArrayList<String> failures = new ArrayList<>();
        for (T route : routes) {
            if (ordered && state.aborted()) {
                if (abortSource.isEmpty()) {
                    terminalReason = "ABORTED";
                    abortSource = "RECEIVER";
                }
                break;
            }
            if (clock.nowMillis() >= deadlineMs) {
                terminalReason = "CHAIN_TIMEOUT";
                break;
            }
            long remainingMs = Math.max(1L, deadlineMs - clock.nowMillis());
            DeliveryOutcome outcome;
            try {
                outcome = delivery.deliver(route, state, remainingMs);
                if (outcome == null) outcome = DeliveryOutcome.failure("NULL_DELIVERY_OUTCOME");
            } catch (Exception error) {
                outcome = DeliveryOutcome.failure(error.getClass().getSimpleName());
            }
            boolean chainExpiredAfterDelivery = clock.nowMillis() >= deadlineMs;
            if (chainExpiredAfterDelivery && outcome.success()) {
                outcome = DeliveryOutcome.timeout("BROADCAST_CHAIN_DEADLINE_EXCEEDED");
            }
            processed++;
            if (outcome.success()) {
                delivered++;
                if (ordered) {
                    state = state.apply(outcome.resultUpdate());
                    if (state.aborted()) {
                        terminalReason = "ABORTED";
                        abortSource = "RECEIVER";
                    }
                }
            } else {
                failed++;
                if (outcome.timedOut()) timedOut++;
                failures.add(label.label(route) + ":" + outcome.failureReason());
                if (stopOnFailure) {
                    state = state.apply(new OrderedBroadcastState.ResultUpdate().abort());
                    terminalReason = outcome.timedOut() ? "TIMEOUT_ABORT" : "FAILURE_ABORT";
                    abortSource = "POLICY";
                }
            }
            if (chainExpiredAfterDelivery) {
                terminalReason = "CHAIN_TIMEOUT";
                if (abortSource.isEmpty()) abortSource = "DEADLINE";
                break;
            }
        }
        int skipped = Math.max(0, routes.size() - processed);
        if ("COMPLETED".equals(terminalReason) && skipped > 0) terminalReason = "SKIPPED";
        return new DispatchReport(routes.size(), delivered, failed, skipped, timedOut,
                failures, state, startedAtMs, deadlineMs, terminalReason, abortSource);
    }

    @FunctionalInterface
    public interface Delivery {
        DeliveryOutcome deliver(BrokerManifestReceiverRuntime.Route route,
                                OrderedBroadcastState currentState,
                                long remainingChainBudgetMs) throws Exception;
    }

    @FunctionalInterface public interface RouteLabel<T> { String label(T route); }
    @FunctionalInterface public interface RouteDelivery<T> {
        DeliveryOutcome deliver(T route, OrderedBroadcastState currentState,
                                long remainingChainBudgetMs) throws Exception;
    }

    public static final class DeliveryOutcome {
        private final boolean success;
        private final boolean timedOut;
        private final String failureReason;
        private final OrderedBroadcastState.ResultUpdate resultUpdate;

        private DeliveryOutcome(boolean success, boolean timedOut, String failureReason,
                                OrderedBroadcastState.ResultUpdate resultUpdate) {
            this.success = success;
            this.timedOut = timedOut;
            this.failureReason = failureReason == null ? "" : failureReason;
            this.resultUpdate = resultUpdate;
        }

        public static DeliveryOutcome success(OrderedBroadcastState.ResultUpdate update) {
            return new DeliveryOutcome(true, false, "", update);
        }

        public static DeliveryOutcome failure(String reason) {
            String value = normalizeReason(reason);
            return new DeliveryOutcome(false, false, value, null);
        }

        public static DeliveryOutcome timeout(String reason) {
            String value = normalizeReason(reason == null ? "ORDERED_RECEIVER_TIMEOUT" : reason);
            return new DeliveryOutcome(false, true, value, null);
        }

        public boolean success() { return success; }
        public boolean timedOut() { return timedOut; }
        public String failureReason() { return failureReason; }
        public OrderedBroadcastState.ResultUpdate resultUpdate() { return resultUpdate; }
    }

    public static final class DispatchReport {
        private final int matchedCount;
        private final int deliveredCount;
        private final int failedCount;
        private final int skippedCount;
        private final int timedOutCount;
        private final List<String> failures;
        private final OrderedBroadcastState finalState;
        private final long startedAtMs;
        private final long deadlineMs;
        private final String terminalReason;
        private final String abortSource;

        DispatchReport(int matchedCount, int deliveredCount, int failedCount,
                       int skippedCount, int timedOutCount, List<String> failures,
                       OrderedBroadcastState finalState, long startedAtMs, long deadlineMs,
                       String terminalReason, String abortSource) {
            this.matchedCount = matchedCount;
            this.deliveredCount = deliveredCount;
            this.failedCount = failedCount;
            this.skippedCount = skippedCount;
            this.timedOutCount = timedOutCount;
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
            this.finalState = finalState;
            this.startedAtMs = startedAtMs;
            this.deadlineMs = deadlineMs;
            this.terminalReason = terminalReason;
            this.abortSource = abortSource;
        }

        public int matchedCount() { return matchedCount; }
        public int deliveredCount() { return deliveredCount; }
        public int failedCount() { return failedCount; }
        public int skippedCount() { return skippedCount; }
        public int timedOutCount() { return timedOutCount; }
        public List<String> failures() { return failures; }
        public OrderedBroadcastState finalState() { return finalState; }
        public int processedCount() { return deliveredCount + failedCount; }
        public long startedAtMs() { return startedAtMs; }
        public long deadlineMs() { return deadlineMs; }
        public String terminalReason() { return terminalReason; }
        public String abortSource() { return abortSource; }
    }

    private static long normalizeChainTimeout(long value) {
        if (value <= 0) return DEFAULT_CHAIN_TIMEOUT_MS;
        return Math.min(value, MAX_CHAIN_TIMEOUT_MS);
    }

    private static long safeAdd(long value, long increment) {
        return increment > 0 && value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static String normalizeReason(String reason) {
        String value = reason == null ? "FAILED" : reason.trim();
        if (value.isEmpty()) value = "FAILED";
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
