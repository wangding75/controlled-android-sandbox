package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.contract.ProcessSlotContract;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProcessSlotConcurrencySelfTest {
    private ProcessSlotConcurrencySelfTest() { }

    public static void main(String[] args) throws Exception {
        AtomicInteger counter = new AtomicInteger();
        SessionRegistry ordinary = new SessionRegistry(ProcessSlotContract.ORDINARY_SLOT_COUNT,
                purpose -> purpose + "-o-" + counter.incrementAndGet());
        SessionRegistry isolated = new SessionRegistry(ProcessSlotContract.ISOLATED_SLOT_COUNT,
                purpose -> purpose + "-i-" + counter.incrementAndGet());

        Set<Integer> ordinarySlots = new HashSet<>();
        for (int i = 0; i < ProcessSlotContract.ORDINARY_SLOT_COUNT; i++) {
            GuestSession session = ordinary.allocate("com.ord" + i, 0, "com.ord" + i, "rev", 1L);
            check(ordinarySlots.add(session.processSlot()), "duplicate ordinary slot");
        }
        check(ordinarySlots.size() == ProcessSlotContract.ORDINARY_SLOT_COUNT, "ordinary 0..63");
        expectNoSlot(() -> ordinary.allocate("com.overflow", 0, "com.overflow", "rev", 2L));

        Set<Integer> isolatedSlots = new HashSet<>();
        for (int i = 0; i < ProcessSlotContract.ISOLATED_SLOT_COUNT; i++) {
            GuestSession session = isolated.allocate("com.iso" + i, 0,
                    "com.iso" + i + ":iso", "rev", 3L);
            check(isolatedSlots.add(session.processSlot()), "duplicate isolated slot");
        }
        check(isolatedSlots.size() == ProcessSlotContract.ISOLATED_SLOT_COUNT, "isolated 0..15");
        expectNoSlot(() -> isolated.allocate("com.iso-overflow", 0, "com.iso-overflow:iso", "rev", 4L));
        check(ordinary.used() == ProcessSlotContract.ORDINARY_SLOT_COUNT,
                "isolated exhaustion must not steal ordinary slots");

        SessionRegistry race = new SessionRegistry(ProcessSlotContract.ORDINARY_SLOT_COUNT,
                purpose -> purpose + "-r-" + counter.incrementAndGet());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sameName = new AtomicInteger();
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    GuestSession session = race.allocate("com.same", 0, "com.same", "rev", 10L);
                    if (session.processSlot() == 0 || session.processSlot() > 0) {
                        sameName.incrementAndGet();
                    }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        check(race.used() == 1, "same processName must share one lease");
        check(sameName.get() == 8, "all waiters must observe the same lease");

        GuestSession other = race.allocate("com.same", 0, "com.same:other", "rev", 11L);
        check(other.processSlot() != race.get("com.same", 0, "com.same").processSlot(),
                "different processName must take a different slot");

        race.transition("com.same", 0, "com.same", 1L, SessionState.PREPARING, 11_500L, "");
        race.transition("com.same", 0, "com.same", 1L, SessionState.READY, 11_750L, "");
        GuestSession died = race.markProcessDied("com.same", 0, "com.same", 1L, 12L, "death");
        check(died.state() == SessionState.RECOVERING, "death enters recovery");
        GuestSession recovered = race.beginRecovery("com.same", 0, "com.same", 1L, 13L);
        check(recovered.generation() == 2L, "recovery advances generation");

        GuestSession user1 = race.allocate("com.multi", 1, "com.multi", "rev", 14L);
        GuestSession user0 = race.allocate("com.multi", 0, "com.multi", "rev", 15L);
        check(user0.processSlot() != user1.processSlot(), "multi-user must not share slots");
        System.out.println("PASS process slot 64/16 concurrency and recovery self-test");
    }

    private static void expectNoSlot(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            if (!"NO_PROCESS_SLOT".equals(expected.getMessage())) {
                throw new AssertionError("wrong exhaustion", expected);
            }
            return;
        }
        throw new AssertionError("expected NO_PROCESS_SLOT");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
