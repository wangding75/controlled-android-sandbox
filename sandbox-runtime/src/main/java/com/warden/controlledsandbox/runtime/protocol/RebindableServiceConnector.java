package com.warden.controlledsandbox.runtime.protocol;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import java.util.Objects;
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

    private static final long DEFAULT_TIMEOUT_MS = 10_000L;
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
    private final Object lock = new Object();

    private Attempt attempt;
    private T service;
    private IBinder binder;
    private boolean closed;
    private int consecutiveFailures;
    private long nextBindAtNanos;
    private Throwable lastFailure;
    private long epoch;

    public RebindableServiceConnector(Context context, Intent intent,
            BinderAdapter<T> adapter, ServiceCloser<T> closer, String serviceName) {
        this(context, intent, adapter, closer, serviceName,
                DEFAULT_TIMEOUT_MS, DEFAULT_INITIAL_RETRY_MS, DEFAULT_MAX_RETRY_MS);
    }

    RebindableServiceConnector(Context context, Intent intent,
            BinderAdapter<T> adapter, ServiceCloser<T> closer, String serviceName,
            long timeoutMs, long initialRetryMs, long maxRetryMs) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.intent = Objects.requireNonNull(intent, "intent");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.closer = closer == null ? ignored -> { } : closer;
        this.serviceName = required(serviceName, "serviceName");
        if (timeoutMs <= 0L || initialRetryMs < 0L || maxRetryMs < initialRetryMs) {
            throw new IllegalArgumentException("Invalid Binder retry timing");
        }
        this.timeoutMs = timeoutMs;
        this.initialRetryMs = initialRetryMs;
        this.maxRetryMs = maxRetryMs;
    }

    public T require() throws Exception {
        long deadline = safeAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        while (true) {
            T current = currentAlive();
            if (current != null) return current;

            Attempt waiting;
            long delayNanos;
            synchronized (lock) {
                ensureOpenLocked();
                current = currentAliveLocked();
                if (current != null) return current;
                long now = System.nanoTime();
                delayNanos = Math.max(0L, nextBindAtNanos - now);
                if (delayNanos == 0L) {
                    if (attempt == null || attempt.completed()) attempt = newAttemptLocked();
                    waiting = attempt;
                } else {
                    waiting = null;
                }
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) throw unavailable();
            if (delayNanos > 0L) {
                sleepNanos(Math.min(delayNanos, remaining));
                continue;
            }

            waiting.startIfNeeded();
            remaining = deadline - System.nanoTime();
            if (remaining <= 0L || !waiting.latch.await(remaining, TimeUnit.NANOSECONDS)) {
                throw unavailable();
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
            failAttempt(target.epoch, target, "BIND_EXCEPTION", error, false);
            return;
        }
        if (!bound) {
            failAttempt(target.epoch, target, "BIND_REJECTED", null, false);
            return;
        }
        boolean staleBinding;
        synchronized (lock) {
            staleBinding = closed || attempt != target;
            if (!staleBinding) target.bound = true;
        }
        if (staleBinding) {
            target.bound = true;
            unbind(target);
        }
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
        if (target == null || target.connection == null || !target.bound) return;
        try { context.unbindService(target.connection); }
        catch (RuntimeException ignored) { }
        target.bound = false;
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
        boolean done;
        IBinder.DeathRecipient deathRecipient;

        Attempt(long epoch) { this.epoch = epoch; }

        void startIfNeeded() {
            synchronized (lock) {
                if (started || done || closed || attempt != this) return;
                started = true;
            }
            start(this);
        }

        boolean completed() { return done; }
    }

    private static final class StaleConnectionException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
