package com.warden.controlledsandbox.runtime.protocol;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Process-local Binder binding state machine with death handling and bounded exponential retry.
 * Callers obtain a live typed capability through {@link #require()} rather than retaining a stale Binder.
 */
public final class RebindableServiceConnector<T> implements AutoCloseable {
    @FunctionalInterface
    public interface BinderAdapter<T> {
        T adapt(IBinder binder) throws Exception;
    }

    @FunctionalInterface
    public interface ServiceCloser<T> {
        void close(T service) throws Exception;
    }

    public record Snapshot(
            boolean connected,
            boolean binding,
            boolean closed,
            int consecutiveFailures,
            long retryDelayMs,
            String lastFailure) { }

    /**
     * A cold-started service process can take longer than ten seconds while its
     * native companions and package authority are brought up together. Keep the
     * bounded retry model, but make the default large enough for a legitimate
     * cold bind to complete instead of reporting a transient outage.
     */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_INITIAL_RETRY_MS = 100L;
    private static final long DEFAULT_MAX_RETRY_MS = 2_000L;

    private final Context context;
    private final Intent intent;
    private final BinderAdapter<T> adapter;
    private final ServiceCloser<T> closer;
    private final String serviceName;
    private final long timeoutMs;
    private final long initialRetryMs;
    private final long maxRetryMs;
    private final Runnable invalidationListener;
    private final Object lock = new Object();

    private Attempt attempt;
    private T service;
    private IBinder binder;
    private boolean closed;
    private int consecutiveFailures;
    private long nextBindAtNanos;
    private Throwable lastFailure;
    private long epoch;
    private CountDownLatch cleanupCompleted;

    public RebindableServiceConnector(Context context, Intent intent,
            BinderAdapter<T> adapter, ServiceCloser<T> closer, String serviceName) {
        this(context, intent, adapter, closer, serviceName,
                DEFAULT_TIMEOUT_MS, DEFAULT_INITIAL_RETRY_MS, DEFAULT_MAX_RETRY_MS, () -> { });
    }

    /**
     * Creates a connector that notifies the owner whenever a published Binder capability is
     * invalidated by death, disconnection, bind timeout or bind failure.  The callback is a
     * local state-fence hook; it must not perform blocking Binder work.
     */
    public RebindableServiceConnector(Context context, Intent intent,
            BinderAdapter<T> adapter, ServiceCloser<T> closer, String serviceName,
            Runnable invalidationListener) {
        this(context, intent, adapter, closer, serviceName,
                DEFAULT_TIMEOUT_MS, DEFAULT_INITIAL_RETRY_MS, DEFAULT_MAX_RETRY_MS,
                invalidationListener);
    }

    RebindableServiceConnector(Context context, Intent intent,
            BinderAdapter<T> adapter, ServiceCloser<T> closer, String serviceName,
            long timeoutMs, long initialRetryMs, long maxRetryMs) {
        this(context, intent, adapter, closer, serviceName, timeoutMs, initialRetryMs,
                maxRetryMs, () -> { });
    }

    RebindableServiceConnector(Context context, Intent intent,
            BinderAdapter<T> adapter, ServiceCloser<T> closer, String serviceName,
            long timeoutMs, long initialRetryMs, long maxRetryMs,
            Runnable invalidationListener) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.intent = Objects.requireNonNull(intent, "intent");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.closer = closer == null ? ignored -> { } : closer;
        this.serviceName = required(serviceName, "serviceName");
        this.invalidationListener = invalidationListener == null ? () -> { }
                : invalidationListener;
        if (timeoutMs <= 0L || initialRetryMs < 0L || maxRetryMs < initialRetryMs) {
            throw new IllegalArgumentException("Invalid Binder retry timing");
        }
        this.timeoutMs = timeoutMs;
        this.initialRetryMs = initialRetryMs;
        this.maxRetryMs = maxRetryMs;
    }

    public T require() throws Exception {
        return requireAttempts(Integer.MAX_VALUE);
    }

    /**
     * Acquires the capability with one bind generation only.  Callers that own a mutating
     * transaction use this entry point so a connector-level retry cannot invisibly replay the
     * beginning of an operation.  A recovery attempt, when policy allows it, must be started and
     * recorded explicitly by the caller.
     */
    public T requireSingleAttempt() throws Exception {
        return requireAttempts(1);
    }

    private T requireAttempts(int maxAttempts) throws Exception {
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
        long deadline = safeAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        Set<Long> observedAttempts = new HashSet<>();
        while (true) {
            T current;
            Attempt waiting;
            DeadCleanup deadCleanup;
            CountDownLatch cleanupWait;
            long delayNanos;
            synchronized (lock) {
                ensureOpenLocked();
                current = currentAliveLocked();
                if (current != null) return current;
                if (cleanupCompleted != null) {
                    cleanupWait = cleanupCompleted;
                    deadCleanup = null;
                    waiting = null;
                    delayNanos = 0L;
                } else if (hasDeadPublishedCapabilityLocked()) {
                    deadCleanup = claimDeadCleanupLocked();
                    cleanupWait = deadCleanup.completed;
                    waiting = null;
                    delayNanos = 0L;
                } else {
                    cleanupWait = null;
                    deadCleanup = null;
                    long now = System.nanoTime();
                    delayNanos = Math.max(0L, nextBindAtNanos - now);
                    if (delayNanos == 0L) {
                        if (attempt == null || attempt.completed()) {
                            if (observedAttempts.size() >= maxAttempts) throw unavailable();
                            attempt = newAttemptLocked();
                        }
                        waiting = attempt;
                        if (!observedAttempts.contains(waiting.epoch)
                                && observedAttempts.size() >= maxAttempts) {
                            throw unavailable();
                        }
                        observedAttempts.add(waiting.epoch);
                    } else {
                        if (observedAttempts.size() >= maxAttempts) throw unavailable();
                        waiting = null;
                    }
                }
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) throw unavailable();
            if (deadCleanup != null) {
                completeDeadCleanup(deadCleanup);
                continue;
            }
            if (cleanupWait != null) {
                if (!cleanupWait.await(remaining, TimeUnit.NANOSECONDS)) throw unavailable();
                continue;
            }
            if (delayNanos > 0L) {
                sleepNanos(Math.min(delayNanos, remaining));
                continue;
            }

            waiting.startIfNeeded();
            if (waiting.completed()) continue;

            remaining = deadline - System.nanoTime();
            long attemptRemaining = waiting.remainingNanos();
            if (attemptRemaining <= 0L) {
                if (timeoutAttempt(waiting)) throw unavailable();
                continue;
            }
            if (remaining <= 0L) {
                timeoutAttempt(waiting);
                throw unavailable();
            }
            long waitNanos = Math.min(remaining, attemptRemaining);
            if (!waiting.latch.await(waitNanos, TimeUnit.NANOSECONDS)) {
                if (timeoutAttempt(waiting)) throw unavailable();
                if (deadline - System.nanoTime() <= 0L) throw unavailable();
            }
        }
    }

    public T current() {
        return currentAlive();
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long delay = Math.max(0L, nextBindAtNanos - System.nanoTime());
            return new Snapshot(currentAliveLocked() != null,
                    attempt != null && !attempt.completed(), closed, consecutiveFailures,
                    TimeUnit.NANOSECONDS.toMillis(delay),
                    lastFailure == null ? "" : String.valueOf(lastFailure.getMessage()));
        }
    }

    /** Forces the next caller to acquire a fresh Binder capability. */
    public void invalidate() {
        invalidateCurrent("BINDER_INVALIDATED", null, true);
    }

    @Override public void close() {
        Attempt staleAttempt;
        T staleService;
        IBinder staleBinder;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            staleAttempt = attempt;
            attempt = null;
            staleService = service;
            service = null;
            staleBinder = binder;
            binder = null;
            if (staleAttempt != null) staleAttempt.latch.countDown();
        }
        unlink(staleBinder, staleAttempt == null ? null : staleAttempt.deathRecipient);
        closeService(staleService);
        unbind(staleAttempt);
    }

    private T currentAlive() {
        synchronized (lock) { return currentAliveLocked(); }
    }

    private T currentAliveLocked() {
        if (service == null || binder == null || !binder.isBinderAlive()) return null;
        return service;
    }

    private boolean hasDeadPublishedCapabilityLocked() {
        if (service == null && binder == null) return false;
        return service == null || binder == null || !binder.isBinderAlive();
    }

    private DeadCleanup claimDeadCleanupLocked() {
        CountDownLatch completed = new CountDownLatch(1);
        cleanupCompleted = completed;
        Attempt staleAttempt = attempt;
        T staleService = service;
        IBinder staleBinder = binder;
        attempt = null;
        service = null;
        binder = null;
        if (staleAttempt != null) {
            staleAttempt.done = true;
            staleAttempt.latch.countDown();
        }
        recordFailureLocked("DEAD_BINDER", null);
        return new DeadCleanup(staleAttempt, staleService, staleBinder, completed);
    }

    private void completeDeadCleanup(DeadCleanup cleanup) {
        try {
            unlink(cleanup.binder,
                    cleanup.attempt == null ? null : cleanup.attempt.deathRecipient);
            closeService(cleanup.service);
            unbind(cleanup.attempt);
            notifyInvalidated();
        } finally {
            synchronized (lock) {
                if (cleanupCompleted == cleanup.completed) cleanupCompleted = null;
                cleanup.completed.countDown();
            }
        }
    }

    private Attempt newAttemptLocked() {
        long currentEpoch = ++epoch;
        Attempt created = new Attempt(currentEpoch);
        created.connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder value) {
                connected(currentEpoch, created, value);
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                disconnected(currentEpoch, created, "SERVICE_DISCONNECTED", null);
            }
            @Override public void onBindingDied(ComponentName name) {
                disconnected(currentEpoch, created, "BINDING_DIED", null);
            }
            @Override public void onNullBinding(ComponentName name) {
                disconnected(currentEpoch, created, "NULL_BINDING", null);
            }
        };
        return created;
    }

    private void start(Attempt target) {
        boolean bound;
        try {
            bound = context.bindService(intent, target.connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            failAttempt(target.epoch, target, "BIND_EXCEPTION", error, true);
            return;
        }
        if (!bound) {
            failAttempt(target.epoch, target, "BIND_REJECTED", null, true);
            return;
        }
        boolean staleBinding;
        synchronized (lock) {
            if (!target.unbindClaimed) target.bound = true;
            staleBinding = closed || attempt != target || target.unbindClaimed;
        }
        if (staleBinding) unbind(target);
    }

    private void connected(long connectedEpoch, Attempt target, IBinder value) {
        T adapted = null;
        try {
            if (value == null) throw new IllegalStateException("NULL_BINDER");
            adapted = adapter.adapt(value);
            if (adapted == null) throw new IllegalStateException("NULL_SERVICE_ADAPTER");
            target.deathRecipient = () -> disconnected(connectedEpoch, target,
                    "BINDER_DIED", null);
            value.linkToDeath(target.deathRecipient, 0);
            synchronized (lock) {
                if (closed || attempt != target || target.epoch != connectedEpoch) {
                    throw new StaleConnectionException();
                }
                service = adapted;
                binder = value;
                target.bound = true;
                target.done = true;
                consecutiveFailures = 0;
                nextBindAtNanos = 0L;
                lastFailure = null;
                target.latch.countDown();
                return;
            }
        } catch (StaleConnectionException stale) {
            unlink(value, target.deathRecipient);
            closeService(adapted);
            unbind(target);
        } catch (Exception error) {
            closeService(adapted);
            failAttempt(connectedEpoch, target, "ADAPTER_FAILED", error, true);
        }
    }

    private void disconnected(long disconnectedEpoch, Attempt target,
            String reason, Throwable cause) {
        failAttempt(disconnectedEpoch, target, reason, cause, true);
    }

    private void invalidateCurrent(String reason, Throwable cause, boolean shouldUnbind) {
        Attempt staleAttempt;
        T staleService;
        IBinder staleBinder;
        synchronized (lock) {
            if (closed) return;
            staleAttempt = attempt;
            attempt = null;
            staleService = service;
            service = null;
            staleBinder = binder;
            binder = null;
            recordFailureLocked(reason, cause);
            if (staleAttempt != null) {
                staleAttempt.done = true;
                staleAttempt.latch.countDown();
            }
        }
        unlink(staleBinder, staleAttempt == null ? null : staleAttempt.deathRecipient);
        closeService(staleService);
        if (shouldUnbind) unbind(staleAttempt);
        notifyInvalidated();
    }

    private boolean timeoutAttempt(Attempt target) {
        synchronized (lock) {
            if (closed || attempt != target || target.done) return false;
            target.done = true;
            attempt = null;
            recordFailureLocked("BIND_TIMEOUT", null);
            target.latch.countDown();
        }
        unbind(target);
        notifyInvalidated();
        return true;
    }

    private void failAttempt(long failedEpoch, Attempt target, String reason,
            Throwable cause, boolean shouldUnbind) {
        T staleService = null;
        IBinder staleBinder = null;
        synchronized (lock) {
            if (attempt != target || target.epoch != failedEpoch) return;
            if (service != null) {
                staleService = service;
                service = null;
            }
            if (binder != null) {
                staleBinder = binder;
                binder = null;
            }
            target.done = true;
            attempt = null;
            recordFailureLocked(reason, cause);
            target.latch.countDown();
        }
        unlink(staleBinder, target.deathRecipient);
        closeService(staleService);
        if (shouldUnbind) unbind(target);
        notifyInvalidated();
    }

    private void notifyInvalidated() {
        try {
            invalidationListener.run();
        } catch (Throwable error) {
            FatalErrorPolicy.rethrowIfFatal(error);
        }
    }

    private void recordFailureLocked(String reason, Throwable cause) {
        consecutiveFailures = Math.min(30, consecutiveFailures + 1);
        long delayMs = retryDelayMs(consecutiveFailures);
        nextBindAtNanos = safeAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(delayMs));
        lastFailure = cause == null ? new IllegalStateException(reason)
                : new IllegalStateException(reason, cause);
    }

    private long retryDelayMs(int failures) {
        if (initialRetryMs == 0L) return 0L;
        long multiplier = 1L << Math.min(20, Math.max(0, failures - 1));
        long delay = initialRetryMs > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : initialRetryMs * multiplier;
        return Math.min(maxRetryMs, delay);
    }

    private IllegalStateException unavailable() {
        Throwable failure;
        synchronized (lock) { failure = lastFailure; }
        return new IllegalStateException(serviceName + " is unavailable", failure);
    }

    private void ensureOpenLocked() {
        if (closed) throw new IllegalStateException(serviceName + " connector is closed");
    }

    private void unbind(Attempt target) {
        ServiceConnection connection;
        synchronized (lock) {
            if (target == null || target.connection == null
                    || !target.bound || target.unbindClaimed) return;
            target.bound = false;
            target.unbindClaimed = true;
            connection = target.connection;
        }
        try { context.unbindService(connection); }
        catch (RuntimeException ignored) { }
    }

    private void closeService(T value) {
        if (value == null) return;
        try { closer.close(value); }
        catch (Exception ignored) { }
    }

    private static void unlink(IBinder value, IBinder.DeathRecipient recipient) {
        if (value == null || recipient == null) return;
        try { value.unlinkToDeath(recipient, 0); }
        catch (RuntimeException ignored) { }
    }

    private static void sleepNanos(long nanos) throws InterruptedException {
        if (nanos <= 0L) return;
        long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
        int extraNanos = (int) (nanos - TimeUnit.MILLISECONDS.toNanos(millis));
        Thread.sleep(millis, extraNanos);
    }

    private static long safeAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private final class Attempt {
        final long epoch;
        final CountDownLatch latch = new CountDownLatch(1);
        ServiceConnection connection;
        boolean started;
        boolean bound;
        boolean unbindClaimed;
        boolean done;
        long deadlineNanos;
        IBinder.DeathRecipient deathRecipient;

        Attempt(long epoch) { this.epoch = epoch; }

        void startIfNeeded() {
            synchronized (lock) {
                if (started || done || closed || attempt != this) return;
                started = true;
                deadlineNanos = safeAdd(System.nanoTime(),
                        TimeUnit.MILLISECONDS.toNanos(timeoutMs));
            }
            start(this);
        }

        long remainingNanos() {
            synchronized (lock) {
                if (!started || done) return done ? 0L : Long.MAX_VALUE;
                return deadlineNanos - System.nanoTime();
            }
        }

        boolean completed() {
            synchronized (lock) { return done; }
        }
    }

    private final class DeadCleanup {
        final Attempt attempt;
        final T service;
        final IBinder binder;
        final CountDownLatch completed;

        DeadCleanup(Attempt attempt, T service, IBinder binder, CountDownLatch completed) {
            this.attempt = attempt;
            this.service = service;
            this.binder = binder;
            this.completed = completed;
        }
    }

    private static final class StaleConnectionException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
