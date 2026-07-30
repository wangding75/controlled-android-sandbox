package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import java.util.concurrent.atomic.AtomicInteger;

public final class IsolatedProcessArchitectureSelfTest {
    private IsolatedProcessArchitectureSelfTest() { }

    public static void main(String[] args) {
        AtomicInteger counter = new AtomicInteger();
        SessionRegistry ordinary = new SessionRegistry(8, purpose -> purpose + "-ordinary-" + counter.incrementAndGet());
        SessionRegistry isolated = new SessionRegistry(4, purpose -> purpose + "-isolated-" + counter.incrementAndGet());

        GuestSession ordinarySession = ordinary.allocate("com.example", 0, "com.example", "rev", 1L);
        GuestSession isolatedSession = isolated.allocate("com.example", 0,
                "com.example:isolated_service", "rev", 2L);
        check(ordinarySession.processSlot() >= 0 && ordinarySession.processSlot() < 8,
                "ordinary slot must remain in ordinary pool");
        check(isolatedSession.processSlot() >= 0 && isolatedSession.processSlot() < 4,
                "isolated slot must remain in isolated pool");
        check(ordinary.capacity() == 8 && isolated.capacity() == 4, "slot capacities changed");
        check(ordinary.used() == 1 && isolated.used() == 1, "slot use must remain independent");

        isolated.transition("com.example", 0, isolatedSession.processName(), 1L,
                SessionState.PREPARING, 3L, "");
        isolated.transition("com.example", 0, isolatedSession.processName(), 1L,
                SessionState.READY, 4L, "");
        GuestSession recovering = isolated.markProcessDied("com.example", 0,
                isolatedSession.processName(), 1L, 5L, "binder died");
        check(recovering.state() == SessionState.RECOVERING, "isolated death must enter recovery");
        GuestSession next = isolated.beginRecovery("com.example", 0, isolatedSession.processName(), 1L, 6L);
        check(next.generation() == 2L && next.state() == SessionState.PREPARING,
                "isolated recovery must advance generation");

        for (int i = 1; i < 4; i++) {
            isolated.allocate("com.example" + i, 0, "com.example" + i + ":isolated", "rev", 10L + i);
        }
        check(isolated.used() == 4, "isolated pool must enforce four active slots");
        expectNoSlot(() -> isolated.allocate("com.overflow", 0, "com.overflow:isolated", "rev", 20L));
        check(ordinary.used() == 1, "isolated saturation must not consume ordinary slots");
        System.out.println("PASS independent four-slot isolated session architecture self-test");
    }

    private static void expectNoSlot(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) {
            if (!"NO_PROCESS_SLOT".equals(expected.getMessage())) {
                throw new AssertionError("wrong slot exhaustion error", expected);
            }
            return;
        }
        throw new AssertionError("fifth isolated lease must fail closed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
