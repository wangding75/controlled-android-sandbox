package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Direct regression coverage for cached virtual system-service capability recovery. */
public final class RuntimeSystemServiceCoordinatorSelfTest {
    private RuntimeSystemServiceCoordinatorSelfTest() { }

    public static void main(String[] args) throws Exception {
        testDeadCachedCapabilityReopensWithinCurrentAttach();
        testConcurrentAttachesShareOneReplacement();
        testDeadReplacementFailsClosedAndNextAttachRecovers();
        System.out.println("PASS Runtime system-service capability recovery self-test");
    }

    private static void testDeadCachedCapabilityReopensWithinCurrentAttach() throws Exception {
        SessionFactory factory = new SessionFactory();
        SessionHandle first = factory.enqueueLive();
        SessionHandle replacement = factory.enqueueLive();
        RuntimeSystemServiceCoordinator coordinator = coordinator(factory);
        GuestSession guest = guest();

        Bundle initial = spec();
        coordinator.attach(guest, initial);
        require(initial.getBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER) == first.binder,
                "initial capability was not attached");
        first.binder.die();

        Bundle recovered = spec();
        coordinator.attach(guest, recovered);
        require(recovered.getBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER) == replacement.binder,
                "current attach did not replace the dead cached capability");
        require(factory.openCount.get() == 2, "dead cache did not trigger exactly one reopen");
        require(first.closeCount.get() == 1, "dead cached capability was not closed exactly once");
        require(coordinator.size() == 1, "replacement capability was not cached");
        coordinator.close();
        require(replacement.closeCount.get() == 1, "coordinator close did not release replacement");
        require(factory.ownerCloseCount.get() == 1, "package client owner was not closed");
    }

    private static void testConcurrentAttachesShareOneReplacement() throws Exception {
        SessionFactory factory = new SessionFactory();
        SessionHandle first = factory.enqueueLive();
        SessionHandle replacement = factory.enqueueLive();
        RuntimeSystemServiceCoordinator coordinator = coordinator(factory);
        GuestSession guest = guest();
        coordinator.attach(guest, spec());
        first.binder.die();
        factory.blockNextOpen();

        int callers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IBinder>> futures = new ArrayList<>();
        for (int index = 0; index < callers; index++) {
            futures.add(executor.submit(() -> {
                start.await();
                Bundle value = spec();
                coordinator.attach(guest, value);
                return value.getBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER);
            }));
        }
        start.countDown();
        require(factory.awaitBlockedOpen(), "replacement open did not enter the controlled barrier");
        require(factory.openCount.get() == 2, "concurrent callers started duplicate reopen attempts");
        factory.releaseBlockedOpen();
        for (Future<IBinder> future : futures) {
            require(future.get(5, TimeUnit.SECONDS) == replacement.binder,
                    "concurrent attach did not receive the shared replacement capability");
        }
        executor.shutdownNow();
        require(factory.openCount.get() == 2, "concurrent callers opened more than one replacement");
        require(first.closeCount.get() == 1, "concurrent recovery closed stale capability repeatedly");
        coordinator.close();
    }

    private static void testDeadReplacementFailsClosedAndNextAttachRecovers() throws Exception {
        SessionFactory factory = new SessionFactory();
        SessionHandle first = factory.enqueueLive();
        SessionHandle deadReplacement = factory.enqueueDead();
        SessionHandle liveReplacement = factory.enqueueLive();
        RuntimeSystemServiceCoordinator coordinator = coordinator(factory);
        GuestSession guest = guest();
        coordinator.attach(guest, spec());
        first.binder.die();

        requireFailure("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DEAD_AFTER_OPEN",
                () -> coordinator.attach(guest, spec()));
        require(coordinator.size() == 0, "dead replacement was published into the cache");
        require(deadReplacement.closeCount.get() == 1, "dead replacement was not closed");

        Bundle recovered = spec();
        coordinator.attach(guest, recovered);
        require(recovered.getBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER) == liveReplacement.binder,
                "attach after dead replacement did not retry from an empty cache");
        require(factory.openCount.get() == 3, "unexpected capability open count after recovery");
        coordinator.close();
    }

    private static RuntimeSystemServiceCoordinator coordinator(SessionFactory factory) {
        return new RuntimeSystemServiceCoordinator(factory::open, factory::closeOwner,
                new TestBinder(true));
    }

    private static GuestSession guest() {
        return new GuestSession("session", "com.example", 0, "com.example:remote", 4,
                7L, SessionState.READY, 0, "revision-7");
    }

    private static Bundle spec() {
        Bundle value = new Bundle();
        value.putInt(RuntimeKeys.VIRTUAL_UID, 10001);
        return value;
    }

    private static void requireFailure(String expected, ThrowingRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected failure " + expected);
        } catch (IllegalStateException error) {
            require(expected.equals(error.getMessage()),
                    "expected " + expected + " but was " + error.getMessage());
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    private static final class SessionFactory {
        private final Queue<SessionHandle> queued = new ArrayDeque<>();
        final AtomicInteger openCount = new AtomicInteger();
        final AtomicInteger ownerCloseCount = new AtomicInteger();
        private volatile CountDownLatch openEntered;
        private volatile CountDownLatch openRelease;

        SessionHandle enqueueLive() { return enqueue(true); }
        SessionHandle enqueueDead() { return enqueue(false); }

        private SessionHandle enqueue(boolean alive) {
            SessionHandle handle = new SessionHandle(new TestBinder(alive));
            queued.add(handle);
            return handle;
        }

        IVirtualSystemServiceSession open(IBinder token, String packageName, int virtualUserId,
                                          int virtualUid, String processName, long generation,
                                          String packageRevision) throws Exception {
            openCount.incrementAndGet();
            CountDownLatch entered = openEntered;
            CountDownLatch release = openRelease;
            if (entered != null && release != null) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("TEST_OPEN_BARRIER_TIMEOUT");
                }
                openEntered = null;
                openRelease = null;
            }
            SessionHandle next = queued.remove();
            return next.session;
        }

        void blockNextOpen() {
            openEntered = new CountDownLatch(1);
            openRelease = new CountDownLatch(1);
        }

        boolean awaitBlockedOpen() throws InterruptedException {
            CountDownLatch entered = openEntered;
            return entered != null && entered.await(5, TimeUnit.SECONDS);
        }

        void releaseBlockedOpen() {
            CountDownLatch release = openRelease;
            if (release != null) release.countDown();
        }

        void closeOwner() { ownerCloseCount.incrementAndGet(); }
    }

    private static final class SessionHandle {
        final TestBinder binder;
        final AtomicInteger closeCount = new AtomicInteger();
        final IVirtualSystemServiceSession session;

        SessionHandle(TestBinder binder) {
            this.binder = binder;
            this.session = (IVirtualSystemServiceSession) Proxy.newProxyInstance(
                    IVirtualSystemServiceSession.class.getClassLoader(),
                    new Class<?>[]{IVirtualSystemServiceSession.class},
                    (proxy, method, args) -> {
                        if ("asBinder".equals(method.getName())) return binder;
                        if ("close".equals(method.getName())) {
                            closeCount.incrementAndGet();
                            return null;
                        }
                        if ("toString".equals(method.getName())) return "SessionHandle";
                        Class<?> type = method.getReturnType();
                        if (!type.isPrimitive()) return null;
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        if (type == byte.class) return (byte) 0;
                        if (type == short.class) return (short) 0;
                        if (type == char.class) return (char) 0;
                        if (type == float.class) return 0F;
                        if (type == double.class) return 0D;
                        return null;
                    });
        }
    }

    private static final class TestBinder implements IBinder {
        private volatile boolean alive;
        TestBinder(boolean alive) { this.alive = alive; }
        @Override public boolean isBinderAlive() { return alive; }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
            if (!alive) throw new RemoteException("dead");
        }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }
        void die() { alive = false; }
    }
}
