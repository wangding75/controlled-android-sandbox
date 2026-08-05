package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class BrokerReceiverRuntimeSelfTest {
    private BrokerReceiverRuntimeSelfTest() { }

    public static void main(String[] args) throws Exception {
        ownerScopedIdsAndResolution();
        rollbackAndUnregisterOwnership();
        actionLimitAndInstanceCleanup();
        fullFilterMatchingAndPriority();
        concurrentReservation();
        System.out.println("PASS broker Receiver production registry self-test");
    }

    private static void ownerScopedIdsAndResolution() {
        BrokerReceiverRuntime runtime = new BrokerReceiverRuntime();
        GuestSession userOne = session("s-u1", 1, 1);
        GuestSession userTwo = session("s-u2", 2, 1);
        runtime.reserveRegistration(request("shared", false), userOne);
        runtime.reserveRegistration(request("shared", false), userTwo);
        check(runtime.size() == 2, "receiver id must be scoped by owner");
        check(runtime.resolve("ACTION_SYNC", 1, userOne.sessionId(), false).size() == 1,
                "user one receiver missing");
        check(runtime.resolve("ACTION_SYNC", 2, userTwo.sessionId(), false).size() == 1,
                "user two receiver missing");
        check(runtime.resolve("ACTION_SYNC", 1, userTwo.sessionId(), false).isEmpty(),
                "virtual-user receiver leaked across users");
        check(runtime.resolve("ACTION_SYNC", 1, "other-session", false).isEmpty(),
                "non-exported receiver leaked across sessions");

        GuestSession exportedOwner = session("s-exported", 1, 1);
        runtime.reserveRegistration(request("public", true), exportedOwner);
        check(runtime.resolve("ACTION_SYNC", 1, "other-session", false).size() == 1,
                "exported receiver unavailable to another internal session");
        check(runtime.resolve("ACTION_SYNC", 1, "", true).size() == 1,
                "external broadcast must resolve only exported receivers");
        check(runtime.removeSession(userOne) == 1 && runtime.size() == 2,
                "session cleanup crossed owner boundary");
    }

    private static void rollbackAndUnregisterOwnership() {
        BrokerReceiverRuntime runtime = new BrokerReceiverRuntime();
        GuestSession owner = session("s-owner", 3, 4);
        BrokerReceiverRuntime.Reservation reservation = runtime.reserveRegistration(
                request("rollback", false), owner);
        runtime.rollbackRegistration(reservation);
        check(runtime.size() == 0, "failed Guest registration was not rolled back");
        runtime.rollbackRegistration(reservation);

        Bundle request = request("owned", false);
        runtime.reserveRegistration(request, owner);
        GuestSession wrongOwner = session("s-wrong", 3, 4);
        boolean denied = false;
        try {
            runtime.requireOwnedRegistration(request, wrongOwner);
        } catch (SecurityException expected) {
            denied = true;
        }
        check(denied, "wrong receiver owner was accepted");
        runtime.requireOwnedRegistration(request, owner);
        runtime.commitUnregister(request, owner);
        check(runtime.size() == 0, "successful unregister leaked broker record");
    }

    private static void actionLimitAndInstanceCleanup() {
        BrokerReceiverRuntime runtime = new BrokerReceiverRuntime();
        GuestSession owner = session("s-limits", 7, 1);
        Bundle tooMany = request("too-many", true);
        ArrayList<String> actions = new ArrayList<>();
        for (int index = 0; index <= com.warden.controlledsandbox.domain.component.receiver.DynamicReceiverRegistry.MAX_ACTIONS_PER_REGISTRATION; index++) {
            actions.add("ACTION_" + index);
        }
        tooMany.putStringArrayList(RuntimeKeys.RECEIVER_ACTIONS, actions);
        try {
            runtime.reserveRegistration(tooMany, owner);
            throw new AssertionError("dynamic Receiver action overflow was accepted");
        } catch (IllegalArgumentException expected) {
            check("DYNAMIC_RECEIVER_ACTION_LIMIT_EXCEEDED".equals(expected.getMessage()),
                    "wrong dynamic Receiver action limit error");
        }
        runtime.reserveRegistration(request("instance", true), owner);
        check(runtime.removeInstance(owner.packageName(), owner.virtualUserId()) == 1,
                "dynamic Receiver instance cleanup");
        check(runtime.size() == 0, "dynamic Receiver instance cleanup leak");
    }

    private static void fullFilterMatchingAndPriority() {
        BrokerReceiverRuntime runtime = new BrokerReceiverRuntime();
        GuestSession owner = session("s-filter", 9, 1);
        Bundle low = request("low", true);
        low.putInt(RuntimeKeys.RECEIVER_PRIORITY, 10);
        low.putStringArrayList(RuntimeKeys.RECEIVER_CATEGORIES,
                new ArrayList<>(List.of("CATEGORY_SYNC")));
        low.putInt(RuntimeKeys.RECEIVER_DATA_RULE_COUNT, 3);
        Bundle scheme = new Bundle();
        scheme.putString(RuntimeKeys.BROADCAST_SCHEME, "content");
        low.putBundle(RuntimeKeys.RECEIVER_DATA_RULE_PREFIX + 0, scheme);
        Bundle host = new Bundle();
        host.putString(RuntimeKeys.BROADCAST_HOST, "guest.example");
        low.putBundle(RuntimeKeys.RECEIVER_DATA_RULE_PREFIX + 1, host);
        Bundle mime = new Bundle();
        mime.putString(RuntimeKeys.BROADCAST_MIME_TYPE, "text/*");
        low.putBundle(RuntimeKeys.RECEIVER_DATA_RULE_PREFIX + 2, mime);
        Bundle high = new Bundle(low);
        high.putString(RuntimeKeys.RECEIVER_ID, "high");
        high.putInt(RuntimeKeys.RECEIVER_PRIORITY, 100);
        low.putString(RuntimeKeys.RECEIVER_PERMISSION, "com.example.SEND_DYNAMIC");
        runtime.reserveRegistration(low, owner);
        runtime.reserveRegistration(high, owner);

        Bundle matching = new Bundle();
        matching.putString(com.warden.controlledsandbox.runtime.protocol.ComponentOperations.ACTION,
                "ACTION_SYNC");
        matching.putStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES,
                new ArrayList<>(List.of("CATEGORY_SYNC")));
        matching.putString(RuntimeKeys.BROADCAST_SCHEME, "content");
        matching.putString(RuntimeKeys.BROADCAST_HOST, "guest.example");
        matching.putString(RuntimeKeys.BROADCAST_MIME_TYPE, "text/plain");
        List<com.warden.controlledsandbox.domain.component.receiver.DynamicReceiverRegistry.Registration>
                matches = runtime.resolve(matching, 9, owner.sessionId(), false);
        check(matches.size() == 2 && "high".equals(matches.get(0).id())
                        && "low".equals(matches.get(1).id()),
                "dynamic receiver full-filter priority order");
        check("com.example.SEND_DYNAMIC".equals(matches.get(1).requiredSenderPermission()),
                "dynamic receiver sender permission was not retained");
        matching.putString(RuntimeKeys.BROADCAST_HOST, "other.example");
        check(runtime.resolve(matching, 9, owner.sessionId(), false).isEmpty(),
                "dynamic receiver data authority mismatch");
        matching.putString(RuntimeKeys.BROADCAST_HOST, "guest.example");
        matching.putStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES,
                new ArrayList<>(List.of("CATEGORY_OTHER")));
        check(runtime.resolve(matching, 9, owner.sessionId(), false).isEmpty(),
                "dynamic receiver category mismatch");
    }

    private static void concurrentReservation() throws Exception {
        BrokerReceiverRuntime runtime = new BrokerReceiverRuntime();
        GuestSession owner = session("s-concurrent", 5, 1);
        Bundle request = request("same", false);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger successes = new AtomicInteger();
        List<BrokerReceiverRuntime.Reservation> reservations =
                java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < workers; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    BrokerReceiverRuntime.Reservation value = runtime.reserveRegistration(request, owner);
                    reservations.add(value);
                    successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                    // One reservation owns this receiver id for this session/generation.
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();
        check(successes.get() == 1 && runtime.size() == 1,
                "duplicate concurrent registrations were accepted");
        runtime.rollbackRegistration(reservations.get(0));
        check(runtime.size() == 0, "concurrent reservation cleanup failed");
    }

    private static Bundle request(String id, boolean exported) {
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.RECEIVER_ID, id);
        request.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.SyncReceiver");
        ArrayList<String> actions = new ArrayList<>();
        actions.add("  ACTION_SYNC  ");
        request.putStringArrayList(RuntimeKeys.RECEIVER_ACTIONS, actions);
        request.putBoolean(RuntimeKeys.RECEIVER_EXPORTED, exported);
        return request;
    }

    private static GuestSession session(String sessionId, int userId, long generation) {
        return new GuestSession(sessionId, "com.example", userId, "com.example:remote", 0,
                generation, SessionState.READY, 0, "");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
