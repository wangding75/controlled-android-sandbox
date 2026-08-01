package com.warden.controlledsandbox.runtime.protocol;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RebindableServiceConnectorSelfTest {
    private RebindableServiceConnectorSelfTest() { }

    public static void main(String[] args) throws Exception {
        reconnectsAfterBinderDeath();
        usesBoundedExponentialBackoff();
        timesOutMissingCallbackAndRebinds();
        timeoutAfterBackoffCancelsAttempt();
        ignoresLateConnectionAfterTimeout();
        closeReleasesWaitingRequire();
        closeDuringSynchronousCallbackDoesNotRearmBinding();
        closesAdaptedCapabilities();
        System.out.println("PASS rebindable service connector self-test");
    }

    private static void reconnectsAfterBinderDeath() throws Exception {
        FakeContext context = new FakeContext();
        List<String> closed = new ArrayList<>();
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), binder -> "service-" + context.bindCount(),
                closed::add, "test service", 1_000L, 0L, 0L);

        FakeBinder first = new FakeBinder();
        context.nextBinder = first;
        require("service-1".equals(connector.require()), "first Binder connection must resolve");
        first.die();
        require(!connector.snapshot().connected(), "Binder death must invalidate cached service");

        context.nextBinder = new FakeBinder();
        require("service-2".equals(connector.require()), "next request must rebind after death");
        require(context.bindCount() == 2, "death recovery must create exactly one new binding");
        require(closed.equals(List.of("service-1")), "dead adapted capability must be closed");
        connector.close();
    }

    private static void usesBoundedExponentialBackoff() throws Exception {
        FakeContext context = new FakeContext();
        context.enqueue(BindBehavior.REJECT, BindBehavior.REJECT,
                BindBehavior.REJECT, BindBehavior.CONNECT);
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), ignored -> "ready", ignored -> { },
                "retry service", 1_000L, 10L, 40L);

        require("ready".equals(connector.require()), "connector must retry a rejected first binding and recover after repeated rejections");
        List<Long> times = context.bindTimesNanos();
        require(times.size() == 4, "three rejected bindings must be followed by one successful binding");
        require(elapsedMillis(times, 0, 1) >= 8L, "first retry must wait about 10 ms");
        require(elapsedMillis(times, 1, 2) >= 18L, "second retry must wait about 20 ms");
        require(elapsedMillis(times, 2, 3) >= 35L, "third retry must honor the 40 ms cap");
        require(connector.snapshot().consecutiveFailures() == 0,
                "successful reconnect must clear failure count");
        connector.close();
    }

    private static void timesOutMissingCallbackAndRebinds() throws Exception {
        FakeContext context = new FakeContext();
        context.enqueue(BindBehavior.NO_CALLBACK, BindBehavior.CONNECT);
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), ignored -> "service-" + context.bindCount(),
                ignored -> { }, "timeout service", 40L, 0L, 0L);

        Throwable failure = captureFailure(connector::require);
        require(hasMessage(failure, "BIND_TIMEOUT"), "missing callback must report BIND_TIMEOUT");
        RebindableServiceConnector.Snapshot timedOut = connector.snapshot();
        require(!timedOut.binding(), "timed-out attempt must not remain binding");
        require(timedOut.consecutiveFailures() == 1,
                "timed-out attempt must increment the failure counter");
        require("BIND_TIMEOUT".equals(timedOut.lastFailure()),
                "snapshot must retain the timeout reason");
        require(context.bindCount() == 1, "first require must create one binding attempt");
        require(context.unbindCount() == 1, "timed-out binding must be safely unbound");

        require("service-2".equals(connector.require()),
                "a later require must start a fresh binding attempt");
        require(context.bindCount() == 2, "recovery must bind exactly once more");
        require(connector.snapshot().consecutiveFailures() == 0,
                "successful recovery must clear timeout failures");
        connector.close();
    }

    private static void timeoutAfterBackoffCancelsAttempt() throws Exception {
        FakeContext context = new FakeContext();
        context.enqueue(BindBehavior.REJECT, BindBehavior.NO_CALLBACK);
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), ignored -> "never", ignored -> { },
                "retry timeout service", 80L, 20L, 20L);

        Throwable failure = captureFailure(connector::require);
        require(hasMessage(failure, "BIND_TIMEOUT"),
                "request deadline after retry backoff must cancel the active attempt");
        require(context.bindCount() == 2, "retry path must reach the no-callback attempt");
        require(context.unbindCount() == 1, "request timeout must unbind the retried attempt");
        require(!connector.snapshot().binding(),
                "request timeout must not leave a retried attempt permanently binding");
        connector.close();
    }

    private static void ignoresLateConnectionAfterTimeout() throws Exception {
        FakeContext context = new FakeContext();
        context.enqueue(BindBehavior.NO_CALLBACK, BindBehavior.CONNECT);
        AtomicInteger adapted = new AtomicInteger();
        List<String> closed = new ArrayList<>();
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), ignored -> "adapted-" + adapted.incrementAndGet(),
                closed::add, "late callback service", 40L, 0L, 0L);

        Throwable failure = captureFailure(connector::require);
        require(hasMessage(failure, "BIND_TIMEOUT"), "first attempt must time out");
        context.connectPending(0, new FakeBinder());
        require(closed.equals(List.of("adapted-1")),
                "late callback capability must be closed instead of published");
        require(!connector.snapshot().connected(), "late callback must not resurrect a stale attempt");
        require(context.unbindCount() == 1, "late callback must not double-unbind the old attempt");

        require("adapted-2".equals(connector.require()),
                "fresh attempt must still connect after a late stale callback");
        connector.close();
        require(context.unbindCount() == 2, "active recovery binding must be unbound on close");
    }

    private static void closeReleasesWaitingRequire() throws Exception {
        FakeContext context = new FakeContext();
        context.enqueue(BindBehavior.NO_CALLBACK);
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), ignored -> "never", ignored -> { },
                "close race service", 5_000L, 0L, 0L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> waiting = executor.submit(() -> captureFailure(connector::require));
            require(context.awaitBind(1, TimeUnit.SECONDS), "require must enter bindService");
            connector.close();
            Throwable failure = waiting.get(1, TimeUnit.SECONDS);
            require(hasMessage(failure, "connector is closed"),
                    "close must release the waiter with a closed-connector failure");
            RebindableServiceConnector.Snapshot snapshot = connector.snapshot();
            require(snapshot.closed(), "snapshot must report close");
            require(!snapshot.binding(), "close must clear the active attempt");
            require(context.unbindCount() == 1, "close must unbind the active no-callback attempt once");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void closeDuringSynchronousCallbackDoesNotRearmBinding() throws Exception {
        FakeContext context = new FakeContext();
        context.enqueue(BindBehavior.CONNECT_AND_BLOCK_RETURN);
        List<String> closed = new ArrayList<>();
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), ignored -> "synchronous", closed::add,
                "synchronous close service", 5_000L, 0L, 0L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> waiting = executor.submit(() -> captureFailure(connector::require));
            require(context.awaitCallback(1, TimeUnit.SECONDS),
                    "synchronous callback must publish before bindService returns");
            connector.close();
            context.releaseBindReturn();
            Throwable failure = waiting.get(1, TimeUnit.SECONDS);
            require(hasMessage(failure, "connector is closed"),
                    "close during synchronous callback must prevent publication after return");
            require(closed.equals(List.of("synchronous")),
                    "close must release the synchronously adapted capability");
            require(context.unbindCount() == 1,
                    "bindService return must not re-arm an already unbound connection");
        } finally {
            context.releaseBindReturn();
            executor.shutdownNow();
        }
    }

    private static void closesAdaptedCapabilities() throws Exception {
        FakeContext context = new FakeContext();
        context.nextBinder = new FakeBinder();
        List<String> closed = new ArrayList<>();
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), ignored -> "session", closed::add,
                "close service", 1_000L, 0L, 0L);
        connector.require();
        connector.close();
        require(closed.equals(List.of("session")), "close must release adapted session");
        require(context.unbindCount() == 1, "close must unbind active connection");
    }

    private enum BindBehavior { CONNECT, CONNECT_AND_BLOCK_RETURN, NO_CALLBACK, REJECT }

    private record PendingBinding(ServiceConnection connection, FakeBinder binder) { }

    private static final class FakeContext extends Context {
        private final Deque<BindBehavior> behaviors = new ArrayDeque<>();
        private final List<PendingBinding> pending = new ArrayList<>();
        private final List<Long> bindTimesNanos = new ArrayList<>();
        private final CountDownLatch bindObserved = new CountDownLatch(1);
        private final CountDownLatch callbackDelivered = new CountDownLatch(1);
        private final CountDownLatch allowBindReturn = new CountDownLatch(1);
        private int bindCount;
        private int unbindCount;
        FakeBinder nextBinder;

        synchronized void enqueue(BindBehavior... values) {
            for (BindBehavior value : values) behaviors.addLast(value);
        }

        @Override public boolean bindService(
                Intent intent, ServiceConnection connection, int flags) {
            BindBehavior behavior;
            FakeBinder value;
            synchronized (this) {
                bindCount++;
                bindTimesNanos.add(System.nanoTime());
                bindObserved.countDown();
                behavior = behaviors.isEmpty()
                        ? BindBehavior.CONNECT : behaviors.removeFirst();
                if (behavior == BindBehavior.REJECT) return false;
                value = nextBinder == null ? new FakeBinder() : nextBinder;
                nextBinder = null;
                if (behavior == BindBehavior.NO_CALLBACK) {
                    pending.add(new PendingBinding(connection, value));
                    return true;
                }
            }
            connection.onServiceConnected(null, value);
            if (behavior == BindBehavior.CONNECT_AND_BLOCK_RETURN) {
                callbackDelivered.countDown();
                try {
                    if (!allowBindReturn.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("bindService return was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("bindService interrupted", interrupted);
                }
            }
            return true;
        }

        @Override public synchronized void unbindService(ServiceConnection connection) {
            unbindCount++;
        }

        synchronized int bindCount() { return bindCount; }
        synchronized int unbindCount() { return unbindCount; }
        synchronized List<Long> bindTimesNanos() { return List.copyOf(bindTimesNanos); }

        boolean awaitBind(long timeout, TimeUnit unit) throws InterruptedException {
            return bindObserved.await(timeout, unit);
        }

        boolean awaitCallback(long timeout, TimeUnit unit) throws InterruptedException {
            return callbackDelivered.await(timeout, unit);
        }

        void releaseBindReturn() { allowBindReturn.countDown(); }

        void connectPending(int index, FakeBinder binder) {
            PendingBinding binding;
            synchronized (this) { binding = pending.get(index); }
            binding.connection().onServiceConnected(null,
                    binder == null ? binding.binder() : binder);
        }
    }

    private static final class FakeBinder implements IBinder {
        private DeathRecipient recipient;
        private boolean alive = true;

        @Override public boolean isBinderAlive() { return alive; }
        @Override public void linkToDeath(DeathRecipient value, int flags) throws RemoteException {
            recipient = value;
        }
        @Override public boolean unlinkToDeath(DeathRecipient value, int flags) {
            if (recipient == value) recipient = null;
            return true;
        }
        void die() {
            alive = false;
            DeathRecipient callback = recipient;
            if (callback != null) callback.binderDied();
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }

    private static Throwable captureFailure(CheckedSupplier<?> action) {
        try {
            action.get();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static boolean hasMessage(Throwable failure, String expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains(expected)) return true;
        }
        return false;
    }

    private static long elapsedMillis(List<Long> times, int start, int end) {
        return TimeUnit.NANOSECONDS.toMillis(times.get(end) - times.get(start));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
