package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.IOrderedReceiverCompletion;

import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression coverage for Receiver ownership extracted from RuntimeBrokerService. */
public final class RuntimeReceiverCoordinatorSelfTest {
    private RuntimeReceiverCoordinatorSelfTest() { }

    public static void main(String[] args) {
        SessionRegistry sessions = new SessionRegistry(2, new SequentialTokens());
        GuestSession allocated = sessions.allocate("com.example", 3, "com.example:remote", "revision", 1L);
        GuestSession preparing = sessions.transition(allocated.packageName(), allocated.virtualUserId(),
                allocated.processName(), allocated.generation(), SessionState.PREPARING, 2L, "");
        GuestSession ready = sessions.transition(preparing.packageName(), preparing.virtualUserId(),
                preparing.processName(), preparing.generation(), SessionState.READY, 3L, "");
        BrokerStateStore store = new BrokerStateStore();
        Bundle prepared = new Bundle();
        prepared.putString(RuntimeKeys.PACKAGE_NAME, ready.packageName());
        prepared.putInt(RuntimeKeys.VIRTUAL_USER_ID, ready.virtualUserId());
        prepared.putString(RuntimeKeys.PROCESS_NAME, ready.processName());
        store.putPrepared(RuntimeBrokerService.ownerKey(ready.packageName(), ready.virtualUserId())
                + ":" + ready.processName(), prepared);
        AtomicInteger deliveries = new AtomicInteger();
        ArrayList<String> orderedDeliveryIds = new ArrayList<>();
        RuntimeReceiverCoordinator coordinator = new RuntimeReceiverCoordinator(
                sessions, store, new FixedClock(), new SequentialTokens(),
                request -> { throw new AssertionError("unexpected process activation"); },
                (sessionId, generation) -> sessions.snapshot().stream()
                        .filter(item -> item.sessionId().equals(sessionId) && item.generation() == generation)
                        .findFirst().orElse(null),
                (slot, request) -> {
                    deliveries.incrementAndGet();
                    if (request.getBoolean(RuntimeKeys.BROADCAST_ORDERED, false)) {
                        String receiverId = request.getString(RuntimeKeys.RECEIVER_ID, "");
                        orderedDeliveryIds.add(receiverId);
                        IOrderedReceiverCompletion callback = IOrderedReceiverCompletion.Stub.asInterface(
                                request.getBinder(RuntimeKeys.ORDERED_RECEIVER_COMPLETION_BINDER));
                        Bundle completion = new Bundle();
                        completion.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN,
                                request.getString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, ""));
                        completion.putString(RuntimeKeys.PACKAGE_NAME, ready.packageName());
                        completion.putInt(RuntimeKeys.VIRTUAL_USER_ID, ready.virtualUserId());
                        completion.putString(RuntimeKeys.SESSION_ID, ready.sessionId());
                        completion.putLong(RuntimeKeys.GENERATION, ready.generation());
                        completion.putString(RuntimeKeys.COMPONENT_CLASS,
                                request.getString(RuntimeKeys.COMPONENT_CLASS, ""));
                        if ("ordered-high".equals(receiverId)) {
                            completion.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, 77);
                            completion.putString(RuntimeKeys.BROADCAST_RESULT_DATA, "dynamic-high");
                            completion.putBoolean(RuntimeKeys.BROADCAST_ABORT, true);
                        }
                        return callback.complete(completion);
                    }
                    Bundle out = new Bundle();
                    out.putString(RuntimeKeys.STATUS, "BROADCAST_DELIVERED");
                    return out;
                });

        Bundle registration = new Bundle();
        registration.putString(RuntimeKeys.RECEIVER_ID, "receiver-1");
        registration.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.SyncReceiver");
        ArrayList<String> actions = new ArrayList<>();
        actions.add("ACTION_SYNC");
        registration.putStringArrayList(RuntimeKeys.RECEIVER_ACTIONS, actions);
        registration.putBoolean(RuntimeKeys.RECEIVER_EXPORTED, false);
        coordinator.reserveRegistration(registration, ready);

        Bundle send = new Bundle();
        send.putString(ComponentOperations.ACTION, "ACTION_SYNC");
        Bundle result = coordinator.dispatchDynamicBroadcast(send, ready);
        require("BROADCAST_DELIVERED".equals(result.getString(RuntimeKeys.STATUS, "")),
                "dynamic broadcast result");
        require(deliveries.get() == 1, "Guest delivery delegated exactly once");
        require(coordinator.lifecycle().snapshot().dynamicRegistrations() == 1,
                "dynamic registration owned by coordinator");

        Bundle high = registration("ordered-high", "ACTION_ORDERED", 100);
        Bundle low = registration("ordered-low", "ACTION_ORDERED", 10);
        coordinator.reserveRegistration(high, ready);
        coordinator.reserveRegistration(low, ready);
        Bundle orderedRequest = new Bundle();
        orderedRequest.putString(ComponentOperations.ACTION, "ACTION_ORDERED");
        orderedRequest.putBoolean(RuntimeKeys.BROADCAST_ORDERED, true);
        orderedRequest.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, 1);
        orderedRequest.putString(RuntimeKeys.BROADCAST_RESULT_DATA, "initial");
        Bundle orderedResult;
        try {
            orderedResult = coordinator.dispatchImplicitManifestBroadcast(orderedRequest, ready, true);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        require("ORDERED_BROADCAST_ABORTED".equals(
                        orderedResult.getString(RuntimeKeys.STATUS, "")),
                "ordered dynamic broadcast abort status");
        require(orderedResult.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, 0) == 77
                        && "dynamic-high".equals(orderedResult.getString(
                                RuntimeKeys.BROADCAST_RESULT_DATA, "")),
                "ordered dynamic broadcast final result state");
        require(orderedResult.getInt(RuntimeKeys.BROADCAST_MATCHED_COUNT, 0) == 2
                        && orderedResult.getInt(RuntimeKeys.BROADCAST_DELIVERED_COUNT, 0) == 1
                        && orderedResult.getInt(RuntimeKeys.BROADCAST_SKIPPED_COUNT, 0) == 1,
                "ordered dynamic broadcast counts");
        require(orderedDeliveryIds.equals(List.of("ordered-high")),
                "ordered dynamic priority and abort chain");

        coordinator.stopSession(ready, "TEST_STOP");
        require(coordinator.lifecycle().snapshot().dynamicRegistrations() == 0,
                "session stop cleans Receiver registrations");
        coordinator.invalidateAll("TEST_CLOSE");
        require(coordinator.lifecycle().snapshot().empty(), "coordinator closes without Receiver leaks");
        System.out.println("PASS Runtime Receiver coordinator extraction self-test");
    }

    private static Bundle registration(String id, String action, int priority) {
        Bundle registration = new Bundle();
        registration.putString(RuntimeKeys.RECEIVER_ID, id);
        registration.putString(RuntimeKeys.COMPONENT_CLASS, "com.example." + id.replace('-', '_'));
        registration.putStringArrayList(RuntimeKeys.RECEIVER_ACTIONS,
                new ArrayList<>(List.of(action)));
        registration.putInt(RuntimeKeys.RECEIVER_PRIORITY, priority);
        registration.putBoolean(RuntimeKeys.RECEIVER_EXPORTED, false);
        return registration;
    }

    private static final class FixedClock implements Clock {
        @Override public long nowMillis() { return 100L; }
    }
    private static final class SequentialTokens implements TokenGenerator {
        private int value;
        @Override public String nextToken(String purpose) { return (purpose == null ? "token" : purpose) + "-" + (++value); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
