package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-Java ordered/unordered manifest broadcast execution policy. */
public final class ManifestBroadcastDispatcher {
    public DispatchReport dispatch(List<BrokerManifestReceiverRuntime.Route> routes,
                                   boolean ordered, boolean stopOnFailure,
                                   OrderedBroadcastState initialState,
                                   Delivery delivery) {
        if (routes == null || initialState == null || delivery == null) {
            throw new IllegalArgumentException("routes, initialState and delivery are required");
        }
        OrderedBroadcastState state = initialState;
        int delivered = 0;
        int failed = 0;
        ArrayList<String> failures = new ArrayList<>();
        for (BrokerManifestReceiverRuntime.Route route : routes) {
            if (ordered && state.aborted()) break;
            DeliveryOutcome outcome;
            try {
                outcome = delivery.deliver(route, state);
                if (outcome == null) outcome = DeliveryOutcome.failure("NULL_DELIVERY_OUTCOME");
            } catch (Exception error) {
                outcome = DeliveryOutcome.failure(error.getClass().getSimpleName());
            }
            if (outcome.success()) {
                delivered++;
                if (ordered) state = state.apply(outcome.resultUpdate());
            } else {
                failed++;
                failures.add(route.receiver().packageName() + "/" + route.receiver().className()
                        + ":" + outcome.failureReason());
                if (stopOnFailure) state = state.apply(new OrderedBroadcastState.ResultUpdate().abort());
            }
        }
        return new DispatchReport(routes.size(), delivered, failed, failures, state);
    }

    @FunctionalInterface
    public interface Delivery {
        DeliveryOutcome deliver(BrokerManifestReceiverRuntime.Route route,
                                OrderedBroadcastState currentState) throws Exception;
    }

    public static final class DeliveryOutcome {
        private final boolean success;
        private final String failureReason;
        private final OrderedBroadcastState.ResultUpdate resultUpdate;

        private DeliveryOutcome(boolean success, String failureReason,
                                OrderedBroadcastState.ResultUpdate resultUpdate) {
            this.success = success;
            this.failureReason = failureReason == null ? "" : failureReason;
            this.resultUpdate = resultUpdate;
        }

        public static DeliveryOutcome success(OrderedBroadcastState.ResultUpdate update) {
            return new DeliveryOutcome(true, "", update);
        }

        public static DeliveryOutcome failure(String reason) {
            String value = reason == null ? "FAILED" : reason.trim();
            return new DeliveryOutcome(false, value.isEmpty() ? "FAILED" : value, null);
        }

        public boolean success() { return success; }
        public String failureReason() { return failureReason; }
        public OrderedBroadcastState.ResultUpdate resultUpdate() { return resultUpdate; }
    }

    public static final class DispatchReport {
        private final int matchedCount;
        private final int deliveredCount;
        private final int failedCount;
        private final List<String> failures;
        private final OrderedBroadcastState finalState;

        DispatchReport(int matchedCount, int deliveredCount, int failedCount,
                       List<String> failures, OrderedBroadcastState finalState) {
            this.matchedCount = matchedCount;
            this.deliveredCount = deliveredCount;
            this.failedCount = failedCount;
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
            this.finalState = finalState;
        }

        public int matchedCount() { return matchedCount; }
        public int deliveredCount() { return deliveredCount; }
        public int failedCount() { return failedCount; }
        public List<String> failures() { return failures; }
        public OrderedBroadcastState finalState() { return finalState; }
        public int processedCount() { return deliveredCount + failedCount; }
    }
}
