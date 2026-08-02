package com.warden.controlledsandbox.framework.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class OneTimeRouteStoreSelfTest {
    private OneTimeRouteStoreSelfTest() {}

    public static void main(String[] args) {
        testOneTimeConsumeAndDefensiveCopies();
        testOwnerAndKindMismatchDoNotBurnToken();
        testExpiryAndCapacityRecovery();
        testWallClockChangesDoNotAffectTtl();
        testGenerationRevocation();
        testConcurrentConsumeAllowsExactlyOneWinner();
        testBounds();
        System.out.println("PASS OneTimeRouteStoreSelfTest");
    }

    private static void testOneTimeConsumeAndDefensiveCopies() {
        MutableClock clock = new MutableClock(1_000L);
        OneTimeRouteStore store = new OneTimeRouteStore(
                clock, 8, 1024, Duration.ofMinutes(2));
        RouteOwner owner = owner(3);
        byte[] source = new byte[] {1, 2, 3};
        RouteToken token = store.put(
                owner,
                RouteKind.ACTIVITY_LAUNCH,
                source,
                Map.of("component", "guest.example/.MainActivity"),
                Duration.ofSeconds(30));
        source[0] = 99;

        RoutePayload payload = store.consume(
                token.value(), owner, RouteKind.ACTIVITY_LAUNCH).orElseThrow();
        check(payload.bytes()[0] == 1, "stored bytes must be defensively copied");
        byte[] returned = payload.bytes();
        returned[1] = 88;
        check(payload.bytes()[1] == 2, "returned bytes must be defensively copied");
        check(store.consume(token.value(), owner, RouteKind.ACTIVITY_LAUNCH).isEmpty(),
                "route token must not be replayable");
        check(store.size() == 0, "consumed route must be removed");
    }

    private static void testOwnerAndKindMismatchDoNotBurnToken() {
        OneTimeRouteStore store = new OneTimeRouteStore(
                Clock.fixed(Instant.ofEpochMilli(2_000L), ZoneOffset.UTC),
                8,
                1024,
                Duration.ofMinutes(2));
        RouteOwner owner = owner(4);
        RouteToken token = store.put(
                owner,
                RouteKind.NEW_INTENT,
                new byte[] {7},
                Map.of(),
                Duration.ofSeconds(30));

        expectSecurity(() -> store.consume(token.value(), owner(5), RouteKind.NEW_INTENT));
        expectSecurity(() -> store.consume(token.value(), owner, RouteKind.ACTIVITY_RESULT));
        check(store.size() == 1, "mismatch must not delete a valid route");
        check(store.consume(token.value(), owner, RouteKind.NEW_INTENT).isPresent(),
                "owner should still be able to consume after rejected attempts");
    }

    private static void testExpiryAndCapacityRecovery() {
        MutableClock clock = new MutableClock(5_000L);
        OneTimeRouteStore store = new OneTimeRouteStore(
                clock, 1, 32, Duration.ofSeconds(10));
        RouteOwner owner = owner(1);
        RouteToken first = store.put(
                owner,
                RouteKind.ACTIVITY_LAUNCH,
                new byte[] {1},
                Map.of(),
                Duration.ofSeconds(1));
        try {
            store.put(
                    owner,
                    RouteKind.ACTIVITY_LAUNCH,
                    new byte[] {2},
                    Map.of(),
                    Duration.ofSeconds(1));
            throw new AssertionError("capacity exhaustion should fail");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("capacity"), "capacity error should be explicit");
        }
        clock.advance(Duration.ofSeconds(1));
        check(store.consume(first.value(), owner, RouteKind.ACTIVITY_LAUNCH).isEmpty(),
                "expired route must not be returned");
        RouteToken second = store.put(
                owner,
                RouteKind.ACTIVITY_LAUNCH,
                new byte[] {2},
                Map.of(),
                Duration.ofSeconds(1));
        check(!second.value().equals(first.value()), "new route should receive a fresh token");
    }

    private static void testWallClockChangesDoNotAffectTtl() {
        MutableClock wallClock = new MutableClock(10_000L);
        MutableElapsedClock elapsedClock = new MutableElapsedClock(500L);
        OneTimeRouteStore store = new OneTimeRouteStore(
                wallClock, elapsedClock::nowMillis, 8, 1024, Duration.ofMinutes(1));
        RouteOwner owner = owner(8);

        RouteToken forwardJump = store.put(
                owner, RouteKind.ACTIVITY_LAUNCH, new byte[] {1}, Map.of(), Duration.ofSeconds(1));
        wallClock.advance(Duration.ofDays(30));
        check(store.consume(forwardJump.value(), owner, RouteKind.ACTIVITY_LAUNCH).isPresent(),
                "wall-clock forward jump expired a monotonic route");

        RouteToken backwardJump = store.put(
                owner, RouteKind.NEW_INTENT, new byte[] {2}, Map.of(), Duration.ofSeconds(1));
        wallClock.advance(Duration.ofDays(-60));
        elapsedClock.advance(Duration.ofMillis(1_001));
        check(store.consume(backwardJump.value(), owner, RouteKind.NEW_INTENT).isEmpty(),
                "wall-clock backward jump extended a monotonic route");
    }

    private static void testGenerationRevocation() {
        OneTimeRouteStore store = new OneTimeRouteStore(
                Clock.fixed(Instant.ofEpochMilli(8_000L), ZoneOffset.UTC),
                8,
                1024,
                Duration.ofMinutes(1));
        store.put(owner(1), RouteKind.ACTIVITY_LAUNCH, new byte[] {1}, Map.of(), Duration.ofSeconds(20));
        store.put(owner(2), RouteKind.NEW_INTENT, new byte[] {2}, Map.of(), Duration.ofSeconds(20));
        RouteToken current = store.put(
                owner(3), RouteKind.ACTIVITY_RESULT, new byte[] {3}, Map.of(), Duration.ofSeconds(20));
        int removed = store.revokeStaleGenerations(
                0, "guest.example", "guest.example:main", 2);
        check(removed == 2, "stale generations should be revoked");
        check(store.size() == 1, "current generation should remain");
        check(store.consume(current.value(), owner(3), RouteKind.ACTIVITY_RESULT).isPresent(),
                "current generation route should remain consumable");
    }

    private static void testConcurrentConsumeAllowsExactlyOneWinner() {
        OneTimeRouteStore store = new OneTimeRouteStore(
                Clock.fixed(Instant.ofEpochMilli(9_000L), ZoneOffset.UTC),
                8,
                1024,
                Duration.ofMinutes(1));
        RouteOwner owner = owner(7);
        RouteToken token = store.put(
                owner, RouteKind.NEW_INTENT, new byte[] {9}, Map.of(), Duration.ofSeconds(20));
        int threadCount = 16;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int index = 0; index < threadCount; index++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    Optional<RoutePayload> payload = store.consume(
                            token.value(), owner, RouteKind.NEW_INTENT);
                    if (payload.isPresent()) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, exception);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    done.countDown();
                }
            }, "route-consumer-" + index);
            thread.start();
        }
        await(ready);
        start.countDown();
        await(done);
        if (failure.get() != null) {
            throw new AssertionError("concurrent consume failed", failure.get());
        }
        check(winners.get() == 1, "exactly one concurrent consumer may receive the payload");
        check(store.size() == 0, "concurrently consumed route must be removed");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", exception);
        }
    }

    private static void testBounds() {
        OneTimeRouteStore store = new OneTimeRouteStore(
                Clock.systemUTC(), 2, 4, Duration.ofSeconds(2));
        try {
            store.put(owner(1), RouteKind.ACTIVITY_LAUNCH, new byte[5], Map.of(), Duration.ofSeconds(1));
            throw new AssertionError("oversized payload should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("byte limit"), "payload bound should be explicit");
        }
        try {
            store.put(owner(1), RouteKind.ACTIVITY_LAUNCH, new byte[0], Map.of(), Duration.ofSeconds(3));
            throw new AssertionError("oversized ttl should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("ttl"), "ttl bound should be explicit");
        }
        try {
            store.put(
                    owner(1),
                    RouteKind.ACTIVITY_LAUNCH,
                    new byte[0],
                    Map.of("k", "x".repeat(4097)),
                    Duration.ofSeconds(1));
            throw new AssertionError("oversized metadata should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("metadata value"),
                    "metadata bound should be explicit");
        }
    }

    private static RouteOwner owner(long generation) {
        return new RouteOwner(0, "guest.example", "guest.example:main", generation);
    }

    private static void expectSecurity(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("security mismatch should fail");
        } catch (SecurityException expected) {
            check(expected.getMessage().contains("mismatch"), "security failure should be explicit");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class MutableElapsedClock {
        private long nowMillis;

        private MutableElapsedClock(long nowMillis) {
            this.nowMillis = nowMillis;
        }

        private long nowMillis() {
            return nowMillis;
        }

        private void advance(Duration duration) {
            nowMillis = Math.addExact(nowMillis, duration.toMillis());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(long epochMillis) {
            instant = Instant.ofEpochMilli(epochMillis);
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
