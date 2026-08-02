package com.warden.controlledsandbox.runtime.guest;

import android.os.IBinder;
import android.os.SystemClock;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Intercepts ActivityManager.finishReceiver for sandbox-issued PendingResult tokens. */
final class OrderedReceiverFinishInterceptor implements FrameworkCallInterceptor, AutoCloseable {
    private final Clock clock;
    private final ScheduledExecutorService expirations;
    private final ConcurrentMap<IBinder, Entry> pending = new ConcurrentHashMap<>();

    OrderedReceiverFinishInterceptor() {
        this(SystemClock::elapsedRealtime);
    }

    OrderedReceiverFinishInterceptor(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expirations = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sandbox-ordered-receiver-timeouts");
            thread.setDaemon(true);
            return thread;
        });
    }

    void register(IBinder token, OrderedReceiverPendingResultBridge bridge, long deadlineMs) {
        if (token == null || bridge == null) throw new IllegalArgumentException("token and bridge are required");
        if (deadlineMs < 1) throw new IllegalArgumentException("deadlineMs must be positive");
        Entry entry = new Entry(bridge, deadlineMs);
        if (pending.putIfAbsent(token, entry) != null) {
            throw new IllegalStateException("ORDERED_RECEIVER_FINISH_TOKEN_COLLISION");
        }
        long delayMs = Math.max(0L, deadlineMs - clock.nowMillis());
        entry.expiration = expirations.schedule(() -> expire(token, entry), delayMs, TimeUnit.MILLISECONDS);
        if (pending.get(token) != entry) cancelExpiration(entry);
    }

    void unregister(IBinder token, OrderedReceiverPendingResultBridge bridge) {
        if (token == null || bridge == null) return;
        Entry entry = pending.get(token);
        if (entry != null && entry.bridge == bridge && pending.remove(token, entry)) {
            cancelExpiration(entry);
        }
    }

    int pendingCount() { return pending.size(); }

    int purgeExpired() {
        int expired = 0;
        long now = clock.nowMillis();
        for (Map.Entry<IBinder, Entry> candidate : pending.entrySet()) {
            Entry entry = candidate.getValue();
            if (now >= entry.deadlineMs && pending.remove(candidate.getKey(), entry)) {
                cancelExpiration(entry);
                entry.bridge.cancelLocal();
                expired++;
            }
        }
        return expired;
    }

    @Override public Interception intercept(String serviceName, Method method, Object[] arguments) {
        if (!"activity-manager".equals(serviceName)
                || method == null || !"finishReceiver".equals(method.getName())
                || arguments == null || arguments.length < 5
                || !(arguments[0] instanceof IBinder)) {
            return Interception.passThrough();
        }
        IBinder token = (IBinder) arguments[0];
        Entry entry = pending.remove(token);
        if (entry == null) {
            return token instanceof OrderedReceiverFinishToken
                    ? Interception.handled(null) : Interception.passThrough();
        }
        cancelExpiration(entry);
        try {
            entry.bridge.completeFromFramework(arguments);
        } catch (Throwable ignored) {
            try {
                // The custom token must never fall through to the real AMS. The Broker independently
                // times out or cancels the lease when completion delivery cannot be acknowledged.
                entry.bridge.cancelLocal();
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
            }
        }
        return Interception.handled(null);
    }

    @Override public void close() {
        for (Map.Entry<IBinder, Entry> candidate : pending.entrySet()) {
            Entry entry = candidate.getValue();
            if (pending.remove(candidate.getKey(), entry)) {
                cancelExpiration(entry);
                entry.bridge.cancelLocal();
            }
        }
        expirations.shutdownNow();
    }

    private void expire(IBinder token, Entry entry) {
        if (pending.remove(token, entry)) entry.bridge.cancelLocal();
    }

    private static void cancelExpiration(Entry entry) {
        ScheduledFuture<?> expiration = entry.expiration;
        if (expiration != null) expiration.cancel(false);
    }

    private static final class Entry {
        final OrderedReceiverPendingResultBridge bridge;
        final long deadlineMs;
        volatile ScheduledFuture<?> expiration;

        Entry(OrderedReceiverPendingResultBridge bridge, long deadlineMs) {
            this.bridge = bridge;
            this.deadlineMs = deadlineMs;
        }
    }
}
