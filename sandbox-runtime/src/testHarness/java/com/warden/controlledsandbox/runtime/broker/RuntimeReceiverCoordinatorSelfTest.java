package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;

import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.util.ArrayList;
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
        RuntimeReceiverCoordinator coordinator = new RuntimeReceiverCoordinator(
                sessions, store, new FixedClock(), new SequentialTokens(),
                request -> { throw new AssertionError("unexpected process activation"); },
                (sessionId, generation) -> sessions.snapshot().stream()
                        .filter(item -> item.sessionId().equals(sessionId) && item.generation() == generation)
                        .findFirst().orElse(null),
                (slot, request) -> {
                    deliveries.incrementAndGet();
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

        coordinator.stopSession(ready, "TEST_STOP");
        require(coordinator.lifecycle().snapshot().dynamicRegistrations() == 0,
                "session stop cleans Receiver registrations");
        coordinator.invalidateAll("TEST_CLOSE");
        require(coordinator.lifecycle().snapshot().empty(), "coordinator closes without Receiver leaks");
        System.out.println("PASS Runtime Receiver coordinator extraction self-test");
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
