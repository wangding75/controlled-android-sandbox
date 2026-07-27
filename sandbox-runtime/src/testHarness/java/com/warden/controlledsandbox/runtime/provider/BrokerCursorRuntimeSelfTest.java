package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BrokerCursorRuntimeSelfTest {
    public static void main(String[] args) throws Exception {
        testQueryCommitAndIdentity();
        testQueryRollbackAndAbort();
        testConcurrentPageReservationAndReplay();
        testExpiryAndInvalidation();
        testCapacity();
        System.out.println("PASS broker cursor lease runtime self-test");
    }

    private static void testQueryCommitAndIdentity() {
        BrokerCursorRuntime runtime = new BrokerCursorRuntime();
        BrokerCursorRuntime.QueryReservation reservation = reserve(runtime, "caller-session", 2,
                "target-session", 5, 0);
        BrokerCursorRuntime.Lease lease = runtime.commitQuery(reservation,
                queryResult(reservation.token(), 100, 0, 0, false), 1);
        require(lease.callerSessionId().equals("caller-session"), "caller session bound");
        require(lease.targetSessionId().equals("target-session"), "target session bound");
        require(runtime.size(1) == 1, "committed cursor counted");

        boolean wrongCaller = false;
        try {
            runtime.reservePage(lease.token(), "wrong", 2, "target-session", 5, 0, 0, 10, 1);
        } catch (SecurityException expected) { wrongCaller = true; }
        require(wrongCaller, "wrong caller rejected");
    }


    private static void testQueryRollbackAndAbort() {
        BrokerCursorRuntime runtime = new BrokerCursorRuntime();
        BrokerCursorRuntime.QueryReservation rolledBack = reserve(runtime, "caller-r", 1,
                "target-r", 1, 0);
        runtime.rollbackQuery(rolledBack);
        require(runtime.size(0) == 0, "failed query reservation rolled back");

        BrokerCursorRuntime.QueryReservation committed = reserve(runtime, "caller-x", 1,
                "target-x", 1, 0);
        BrokerCursorRuntime.Lease lease = runtime.commitQuery(committed,
                queryResult(committed.token(), 4, 0, 0, false), 1);
        require(runtime.abort(lease.token()) != null, "failed page abort removes lease");
        require(runtime.size(1) == 0, "aborted cursor absent");
    }

    private static void testConcurrentPageReservationAndReplay() throws Exception {
        BrokerCursorRuntime runtime = new BrokerCursorRuntime();
        BrokerCursorRuntime.QueryReservation query = reserve(runtime, "caller-session", 2,
                "target-session", 5, 0);
        BrokerCursorRuntime.Lease lease = runtime.commitQuery(query,
                queryResult(query.token(), 50, 0, 0, false), 1);

        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<BrokerCursorRuntime.PageReservation> winner = new AtomicReference<>();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    BrokerCursorRuntime.PageReservation page = runtime.reservePage(lease.token(),
                            "caller-session", 2, "target-session", 5, 0, 0, 10, 1);
                    winner.set(page);
                    winners.incrementAndGet();
                } catch (RuntimeException expected) { }
                return null;
            }));
        }
        require(ready.await(5, TimeUnit.SECONDS), "page contenders ready");
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();
        require(winners.get() == 1, "one broker page reservation winner");

        BrokerCursorRuntime.Lease advanced = runtime.commitPage(winner.get(),
                pageResult(10, 10, 1, false), 2);
        require(advanced.nextOffset() == 10 && advanced.nextSequence() == 1, "page state advanced");

        boolean replay = false;
        try {
            runtime.reservePage(lease.token(), "caller-session", 2, "target-session", 5,
                    0, 0, 10, 2);
        } catch (SecurityException expected) { replay = true; }
        require(replay, "page replay rejected");

        BrokerCursorRuntime.TerminalReservation terminal = runtime.reserveTerminal(lease.token(),
                "caller-session", 2, "target-session", 5, 1, 2);
        runtime.completeTerminal(terminal);
        require(runtime.size(2) == 0, "terminal removes cursor");
    }

    private static void testExpiryAndInvalidation() {
        BrokerCursorRuntime runtime = new BrokerCursorRuntime();
        BrokerCursorRuntime.QueryReservation expiring = reserve(runtime, "caller-a", 1,
                "target-a", 1, 0);
        runtime.commitQuery(expiring, queryResult(expiring.token(), 1, 0, 0, false), 0);
        require(runtime.purgeExpired(BrokerCursorRuntime.LEASE_TTL_MS + 1).size() == 1,
                "expired cursor removed");

        BrokerCursorRuntime.QueryReservation callerLease = reserve(runtime, "caller-b", 3,
                "target-b", 4, 10);
        runtime.commitQuery(callerLease, queryResult(callerLease.token(), 1, 0, 0, false), 11);
        require(runtime.invalidateSession("caller-b", 3).size() == 1, "caller death invalidates cursor");

        BrokerCursorRuntime.QueryReservation targetLease = reserve(runtime, "caller-c", 5,
                "target-c", 6, 20);
        runtime.commitQuery(targetLease, queryResult(targetLease.token(), 1, 0, 0, false), 21);
        require(runtime.invalidateSession("target-c", 6).size() == 1, "target death invalidates cursor");
    }

    private static void testCapacity() {
        BrokerCursorRuntime runtime = new BrokerCursorRuntime();
        for (int index = 0; index < BrokerCursorRuntime.MAX_ACTIVE_LEASES; index++) {
            BrokerCursorRuntime.QueryReservation reservation = reserve(runtime,
                    "caller-" + index, 1, "target-" + index, 1, 0);
            runtime.commitQuery(reservation, queryResult(reservation.token(), 1, 0, 0, false), 1);
        }
        boolean exhausted = false;
        try { reserve(runtime, "overflow", 1, "overflow-target", 1, 1); }
        catch (IllegalStateException expected) { exhausted = true; }
        require(exhausted, "broker cursor capacity enforced");
    }

    private static BrokerCursorRuntime.QueryReservation reserve(BrokerCursorRuntime runtime,
                                                                 String callerSession, long callerGeneration,
                                                                 String targetSession, long targetGeneration,
                                                                 long now) {
        return runtime.reserveQuery("u1:caller", callerSession, callerGeneration,
                "u1:target", "target", 1, "target:provider", targetSession,
                targetGeneration, "content://authority/items", 1, now);
    }

    private static Bundle queryResult(String token, int rows, int nextOffset,
                                      long nextSequence, boolean endReached) {
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "CURSOR_READY");
        result.putString(RuntimeKeys.CURSOR_TOKEN, token);
        result.putInt(RuntimeKeys.CURSOR_TOTAL_ROWS, rows);
        result.putInt(RuntimeKeys.CURSOR_NEXT_OFFSET, nextOffset);
        result.putLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, nextSequence);
        result.putBoolean(RuntimeKeys.CURSOR_END_REACHED, endReached);
        return result;
    }

    private static Bundle pageResult(int emitted, int nextOffset, long nextSequence, boolean endReached) {
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "CURSOR_READY");
        result.putInt(RuntimeKeys.CURSOR_ROWS_RETURNED, emitted);
        result.putInt(RuntimeKeys.CURSOR_NEXT_OFFSET, nextOffset);
        result.putLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, nextSequence);
        result.putBoolean(RuntimeKeys.CURSOR_END_REACHED, endReached);
        return result;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
