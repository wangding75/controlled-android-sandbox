package com.warden.controlledsandbox.runtime.component.receiver;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class OrderedReceiverTokenRegistrySelfTest {
    private OrderedReceiverTokenRegistrySelfTest() { }

    public static void main(String[] args) throws Exception {
        completionAndReplay();
        identityAndTimeout();
        sessionCleanup();
        capacityTimeoutAndCollisionLimits();
        concurrentSingleWinner();
        bundleAdapter();
        System.out.println("PASS ordered Receiver token registry self-test");
    }

    private static void completionAndReplay() throws Exception {
        FakeClock clock = new FakeClock(100);
        OrderedReceiverTokenRegistry registry = new OrderedReceiverTokenRegistry(clock, sequence("t1"));
        GuestSession target = session("s1", 2, 7);
        OrderedReceiverTokenRegistry.Lease lease = registry.issue(target, "com.example.R", 500);
        OrderedBroadcastState.ResultUpdate update = new OrderedBroadcastState.ResultUpdate()
                .resultCode(8).resultData("done").resultExtras(Map.of("k", "v")).abort();
        OrderedReceiverTokenRegistry.CompletionDecision accepted = registry.complete(
                lease.token(), identity(target, "com.example.R"), update);
        require(accepted.accepted(), "first completion rejected");
        OrderedReceiverTokenRegistry.AwaitResult awaited = registry.await(lease);
        require(awaited.completed() && awaited.state() == OrderedReceiverTokenRegistry.State.COMPLETED,
                "completed token did not unblock");
        OrderedBroadcastState finalState = OrderedBroadcastState.initial(0, "", Map.of()).apply(awaited.update());
        require(finalState.resultCode() == 8 && "done".equals(finalState.resultData())
                && "v".equals(finalState.resultExtras().get("k")) && finalState.aborted(),
                "result update was not preserved");
        require("ORDERED_RECEIVER_REPLAY".equals(registry.complete(
                lease.token(), identity(target, "com.example.R"), update).status()),
                "replay was not rejected");
    }

    private static void identityAndTimeout() throws Exception {
        FakeClock clock = new FakeClock(1_000);
        OrderedReceiverTokenRegistry registry = new OrderedReceiverTokenRegistry(clock, sequence("t2", "t3"));
        GuestSession target = session("s2", 1, 4);
        OrderedReceiverTokenRegistry.Lease lease = registry.issue(target, "Receiver", 10);
        OrderedReceiverTokenRegistry.Identity wrong = new OrderedReceiverTokenRegistry.Identity(
                target.packageName(), target.virtualUserId(), target.sessionId(), target.generation() + 1, "Receiver");
        require("ORDERED_RECEIVER_IDENTITY_MISMATCH".equals(
                registry.complete(lease.token(), wrong, new OrderedBroadcastState.ResultUpdate()).status()),
                "wrong generation was accepted");
        clock.now = 1_011;
        require("ORDERED_RECEIVER_LATE_COMPLETION".equals(registry.complete(
                lease.token(), identity(target, "Receiver"), new OrderedBroadcastState.ResultUpdate()).status()),
                "late callback was not rejected");
        OrderedReceiverTokenRegistry.AwaitResult timeout = registry.await(lease);
        require(!timeout.completed() && timeout.state() == OrderedReceiverTokenRegistry.State.TIMED_OUT,
                "timeout was not terminal");
    }

    private static void sessionCleanup() throws Exception {
        FakeClock clock = new FakeClock(10);
        OrderedReceiverTokenRegistry registry = new OrderedReceiverTokenRegistry(clock, sequence("a", "b"));
        GuestSession first = session("first", 0, 1);
        GuestSession second = session("second", 0, 2);
        OrderedReceiverTokenRegistry.Lease one = registry.issue(first, "A", 1000);
        OrderedReceiverTokenRegistry.Lease two = registry.issue(second, "B", 1000);
        require(registry.cancelSession(first, "SESSION_DIED") == 1, "session cleanup count");
        require(registry.pendingCount() == 1, "session cleanup removed unrelated token");
        require(registry.await(one).state() == OrderedReceiverTokenRegistry.State.CANCELLED,
                "cancelled token did not unblock");
        require(registry.cancel(two, "MANUAL"), "specific cancel failed");
    }


    private static void capacityTimeoutAndCollisionLimits() throws Exception {
        FakeClock clock = new FakeClock(50);
        AtomicInteger ids = new AtomicInteger();
        OrderedReceiverTokenRegistry registry = new OrderedReceiverTokenRegistry(
                clock, purpose -> "capacity-" + ids.incrementAndGet());
        GuestSession target = session("capacity-session", 9, 1);
        OrderedReceiverTokenRegistry.Lease first = registry.issue(
                target, "CapacityReceiver", OrderedReceiverTokenRegistry.MAX_TIMEOUT_MS + 99_999L);
        require(first.deadlineMs() - first.issuedAtMs() == OrderedReceiverTokenRegistry.MAX_TIMEOUT_MS,
                "timeout was not clamped");
        for (int i = 1; i < OrderedReceiverTokenRegistry.MAX_ACTIVE; i++) {
            registry.issue(target, "CapacityReceiver" + i, 1_000);
        }
        require(registry.pendingCount() == OrderedReceiverTokenRegistry.MAX_ACTIVE,
                "active token capacity count");
        try {
            registry.issue(target, "OverflowReceiver", 1_000);
            throw new AssertionError("capacity overflow was accepted");
        } catch (IllegalStateException expected) {
            require("ORDERED_RECEIVER_CAPACITY_EXCEEDED".equals(expected.getMessage()),
                    "wrong capacity error");
        }
        require(registry.cancelInstance(target.packageName(), target.virtualUserId(), "INSTANCE_STOP")
                        == OrderedReceiverTokenRegistry.MAX_ACTIVE,
                "instance cleanup did not cancel all tokens");

        OrderedReceiverTokenRegistry collisions = new OrderedReceiverTokenRegistry(
                clock, purpose -> "same-token");
        collisions.issue(target, "First", 1_000);
        try {
            collisions.issue(target, "Second", 1_000);
            throw new AssertionError("token collision was accepted");
        } catch (IllegalStateException expected) {
            require("ORDERED_RECEIVER_TOKEN_COLLISION".equals(expected.getMessage()),
                    "wrong token collision error");
        }
    }

    private static void concurrentSingleWinner() throws Exception {
        FakeClock clock = new FakeClock(0);
        OrderedReceiverTokenRegistry registry = new OrderedReceiverTokenRegistry(clock, sequence("race"));
        GuestSession target = session("race-session", 3, 9);
        OrderedReceiverTokenRegistry.Lease lease = registry.issue(target, "RaceReceiver", 1000);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < workers; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (registry.complete(lease.token(), identity(target, "RaceReceiver"),
                            new OrderedBroadcastState.ResultUpdate().resultCode(1)).accepted()) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();
        require(accepted.get() == 1, "concurrent completion had multiple winners");
    }

    private static void bundleAdapter() throws Exception {
        FakeClock clock = new FakeClock(0);
        BrokerOrderedReceiverRuntime runtime = new BrokerOrderedReceiverRuntime(clock, sequence("bundle"));
        GuestSession target = session("bundle-session", 5, 3);
        OrderedReceiverTokenRegistry.Lease lease = runtime.issue(target, "BundleReceiver", 1000);
        Bundle completion = new Bundle();
        completion.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, lease.token());
        completion.putString(RuntimeKeys.PACKAGE_NAME, target.packageName());
        completion.putInt(RuntimeKeys.VIRTUAL_USER_ID, target.virtualUserId());
        completion.putString(RuntimeKeys.SESSION_ID, target.sessionId());
        completion.putLong(RuntimeKeys.GENERATION, target.generation());
        completion.putString(RuntimeKeys.COMPONENT_CLASS, "BundleReceiver");
        completion.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, 12);
        completion.putString(RuntimeKeys.BROADCAST_RESULT_DATA, "bundle");
        Bundle extras = new Bundle();
        extras.putString("x", "y");
        completion.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS, extras);
        Bundle ack = runtime.complete(completion);
        require(ack.getBoolean(RuntimeKeys.ORDERED_RECEIVER_ACCEPTED, false), "bundle completion rejected");
        OrderedReceiverTokenRegistry.AwaitResult result = runtime.await(lease);
        require(result.completed() && result.update().resultCode() == 12,
                "bundle result was not decoded");

        OrderedReceiverTokenRegistry.Lease malformedLease = runtime.issue(
                target, "MalformedReceiver", 1000);
        Bundle malformed = new Bundle();
        malformed.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, malformedLease.token());
        malformed.putString(RuntimeKeys.PACKAGE_NAME, target.packageName());
        malformed.putInt(RuntimeKeys.VIRTUAL_USER_ID, target.virtualUserId());
        malformed.putString(RuntimeKeys.SESSION_ID, target.sessionId());
        malformed.putLong(RuntimeKeys.GENERATION, target.generation());
        malformed.putString(RuntimeKeys.COMPONENT_CLASS, "MalformedReceiver");
        Bundle invalidExtras = new Bundle();
        invalidExtras.putInt("not-string", 7);
        malformed.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS, invalidExtras);
        Bundle rejected = runtime.complete(malformed);
        require(!rejected.getBoolean(RuntimeKeys.ORDERED_RECEIVER_ACCEPTED, true)
                        && "CANCELLED".equals(rejected.getString(RuntimeKeys.ORDERED_RECEIVER_STATE, "")),
                "malformed completion did not terminally reject token");
        require(runtime.await(malformedLease).state() == OrderedReceiverTokenRegistry.State.CANCELLED,
                "malformed completion left Broker waiting for timeout");
    }

    private static OrderedReceiverTokenRegistry.Identity identity(GuestSession session, String receiver) {
        return new OrderedReceiverTokenRegistry.Identity(session.packageName(), session.virtualUserId(),
                session.sessionId(), session.generation(), receiver);
    }

    private static GuestSession session(String id, int user, long generation) {
        return new GuestSession(id, "com.example", user, "com.example:receiver", 0,
                generation, SessionState.READY, 0, "");
    }

    private static TokenGenerator sequence(String... values) {
        Queue<String> queue = new ArrayDeque<>();
        java.util.Collections.addAll(queue, values);
        AtomicInteger fallback = new AtomicInteger();
        return purpose -> queue.isEmpty() ? "fallback-" + fallback.incrementAndGet() : queue.remove();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeClock implements Clock {
        long now;
        FakeClock(long now) { this.now = now; }
        @Override public long nowMillis() { return now; }
    }
}
