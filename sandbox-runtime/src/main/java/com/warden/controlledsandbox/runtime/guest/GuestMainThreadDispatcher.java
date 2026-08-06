package com.warden.controlledsandbox.runtime.guest;

import android.os.Handler;
import android.os.Looper;
import com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serializes Android component lifecycle work onto the Guest process main thread.
 *
 * <p>A Guest main-thread callback may synchronously call the Broker. The Broker can in turn call
 * the same Guest process before returning. A plain {@code Handler.post()+await()} implementation
 * deadlocks in that situation because the main thread is waiting for the Broker. This dispatcher
 * therefore moves the outbound Broker transaction to a worker and lets the waiting main thread
 * drain only the explicitly queued Guest lifecycle work until the transaction completes.</p>
 */
final class GuestMainThreadDispatcher implements AutoCloseable {
    static final long DEFAULT_TIMEOUT_MS = 15_000L;

    private static final AtomicInteger WORKER_IDS = new AtomicInteger();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BlockingQueue<PendingTask<?>> pending = new LinkedBlockingQueue<>();
    private final ConcurrentMap<Handler, BlockingQueue<PendingTask<?>>> handlerPending =
            new ConcurrentHashMap<>();
    private final ThreadLocal<Handler> activeHandler = new ThreadLocal<>();
    private final ExecutorService brokerWorkers;
    private final ClassLoader guestClassLoader;
    private volatile boolean closed;

    GuestMainThreadDispatcher(ClassLoader guestClassLoader) {
        this.guestClassLoader = Objects.requireNonNull(guestClassLoader, "guestClassLoader");
        this.brokerWorkers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable,
                    "guest-broker-wait-" + WORKER_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    <T> T call(Callable<T> action) {
        return call(action, DEFAULT_TIMEOUT_MS);
    }

    <T> T call(Callable<T> action, long timeoutMs) {
        requireOpen();
        Objects.requireNonNull(action, "action");
        if (isMainThread()) return runWithGuestClassLoader(action);

        PendingTask<T> task = new PendingTask<>(action);
        pending.add(task);
        if (!mainHandler.post(this::drainOnMain)) {
            pending.remove(task);
            throw new IllegalStateException("GUEST_MAIN_HANDLER_REJECTED");
        }
        return task.await(timeoutMs);
    }

    void run(ThrowingRunnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    /** Executes one synchronous Broker call while preserving reentrant Guest callback delivery. */
    <T> T callBroker(Callable<T> action) {
        requireOpen();
        Objects.requireNonNull(action, "action");
        Handler handler = activeHandler.get();
        if (!isMainThread() && handler == null) return unchecked(action);

        Future<T> brokerCall = brokerWorkers.submit(action);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEFAULT_TIMEOUT_MS);
        try {
            while (!brokerCall.isDone()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    brokerCall.cancel(true);
                    throw new IllegalStateException("GUEST_BROKER_CALLBACK_TIMEOUT");
                }
                PendingTask<?> task = pollReentrant(handler, remaining);
                if (task != null) task.executeWithClassLoader(this);
            }
            return brokerCall.get(0L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            brokerCall.cancel(true);
            throw new IllegalStateException("GUEST_BROKER_CALLBACK_INTERRUPTED", error);
        } catch (ExecutionException error) {
            throw propagate(error.getCause());
        } catch (TimeoutException impossible) {
            throw new IllegalStateException("GUEST_BROKER_RESULT_UNAVAILABLE", impossible);
        }
    }

    <T> T callOnHandler(Handler handler, Callable<T> action) {
        requireOpen();
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(action, "action");
        if (handler.getLooper() == Looper.getMainLooper()) return call(action);
        if (activeHandler.get() == handler) return runWithGuestClassLoader(action);

        BlockingQueue<PendingTask<?>> queue = handlerPending.computeIfAbsent(
                handler, ignored -> new LinkedBlockingQueue<>());
        PendingTask<T> task = new PendingTask<>(action);
        queue.add(task);
        if (!handler.post(() -> drainOnHandler(handler, queue))) {
            queue.remove(task);
            throw new IllegalStateException("GUEST_HANDLER_REJECTED");
        }
        return isMainThread() ? awaitWhilePumpingMain(task) : task.await(DEFAULT_TIMEOUT_MS);
    }

    private PendingTask<?> pollReentrant(Handler handler, long remainingNanos)
            throws InterruptedException {
        long waitMillis = Math.max(1L, Math.min(
                TimeUnit.NANOSECONDS.toMillis(remainingNanos), 25L));
        BlockingQueue<PendingTask<?>> queue = handler == null
                ? pending : handlerPending.computeIfAbsent(handler, ignored -> new LinkedBlockingQueue<>());
        return queue.poll(waitMillis, TimeUnit.MILLISECONDS);
    }

    private <T> T awaitWhilePumpingMain(PendingTask<T> task) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEFAULT_TIMEOUT_MS);
        while (!task.complete()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                IllegalStateException timeout = new IllegalStateException("GUEST_HANDLER_TIMEOUT");
                task.fail(timeout);
                throw timeout;
            }
            try {
                PendingTask<?> reentrant = pollReentrant(null, remaining);
                if (reentrant != null) reentrant.executeWithClassLoader(this);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GUEST_HANDLER_INTERRUPTED", error);
            }
        }
        return task.result();
    }

    boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    @Override public void close() {
        closed = true;
        brokerWorkers.shutdownNow();
        PendingTask<?> task;
        while ((task = pending.poll()) != null) {
            task.fail(new IllegalStateException("GUEST_MAIN_DISPATCHER_CLOSED"));
        }
        for (BlockingQueue<PendingTask<?>> queue : handlerPending.values()) {
            while ((task = queue.poll()) != null) {
                task.fail(new IllegalStateException("GUEST_MAIN_DISPATCHER_CLOSED"));
            }
        }
        handlerPending.clear();
    }

    private void drainOnHandler(Handler handler, BlockingQueue<PendingTask<?>> queue) {
        Handler previous = activeHandler.get();
        activeHandler.set(handler);
        try {
            PendingTask<?> task;
            while ((task = queue.poll()) != null) task.executeWithClassLoader(this);
        } finally {
            if (previous == null) activeHandler.remove();
            else activeHandler.set(previous);
        }
    }

    private void drainOnMain() {
        if (!isMainThread()) throw new IllegalStateException("GUEST_MAIN_DISPATCH_WRONG_THREAD");
        PendingTask<?> task;
        while ((task = pending.poll()) != null) task.executeOnMain(this);
    }

    private <T> T runWithGuestClassLoader(Callable<T> action) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(guestClassLoader);
            return action.call();
        } catch (Throwable error) {
            FatalErrorPolicy.rethrowIfFatal(error);
            throw propagate(error);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GUEST_MAIN_DISPATCHER_CLOSED");
    }

    private static <T> T unchecked(Callable<T> action) {
        try {
            return action.call();
        } catch (Throwable error) {
            FatalErrorPolicy.rethrowIfFatal(error);
            throw propagate(error);
        }
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException runtime) return runtime;
        if (error instanceof Error fatal) throw fatal;
        return new IllegalStateException(error);
    }

    interface ThrowingRunnable { void run() throws Exception; }

    private static final class PendingTask<T> {
        private final Callable<T> action;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch complete = new CountDownLatch(1);
        private final AtomicReference<T> result = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        PendingTask(Callable<T> action) { this.action = action; }

        void executeOnMain(GuestMainThreadDispatcher dispatcher) {
            executeWithClassLoader(dispatcher);
        }

        void executeWithClassLoader(GuestMainThreadDispatcher dispatcher) {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                result.set(dispatcher.runWithGuestClassLoader(action));
            } catch (Throwable error) {
                failure.set(error);
                FatalErrorPolicy.rethrowIfFatal(error);
            } finally {
                complete.countDown();
            }
        }

        boolean complete() { return complete.getCount() == 0L; }

        T result() {
            Throwable error = failure.get();
            if (error != null) throw propagate(error);
            return result.get();
        }

        T await(long timeoutMs) {
            try {
                if (!complete.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS)) {
                    IllegalStateException timeout = new IllegalStateException("GUEST_MAIN_THREAD_TIMEOUT");
                    fail(timeout);
                    throw timeout;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GUEST_MAIN_THREAD_INTERRUPTED", error);
            }
            Throwable error = failure.get();
            if (error != null) throw propagate(error);
            return result.get();
        }

        void fail(Throwable error) {
            if (!claimed.compareAndSet(false, true)) return;
            failure.set(error);
            complete.countDown();
        }
    }
}
