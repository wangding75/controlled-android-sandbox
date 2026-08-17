package com.warden.controlledsandbox.runtime.provider;

import android.os.IBinder;
import android.os.RemoteException;
import com.warden.controlledsandbox.contract.IProviderQueryCancellation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Broker authority for cancellation of a Provider call before its Cursor lease is committed.
 *
 * <p>The query id is caller-created only as a correlation id.  Ownership is still fixed by the
 * Broker to the caller and target Session/generation pair.  A bounded pre-cancel tombstone closes
 * the race where the cancellation Binder call arrives before the first query has installed its
 * target endpoint.</p>
 */
public final class BrokerProviderQueryCancellation {
    private static final long ENTRY_TTL_MS = 120_000L;
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_TERMINAL_ENTRIES = 256;

    public static final class Handle {
        private final String queryId;
        private final Channel channel;

        private Handle(String queryId, Channel channel) {
            this.queryId = queryId;
            this.channel = channel;
        }

        public String queryId() { return queryId; }
        public IBinder channelBinder() { return channel.asBinder(); }
    }

    private static final class Entry {
        private final String queryId;
        private final String callerInstance;
        private final String callerSessionId;
        private final long callerGeneration;
        private final String targetInstance;
        private final String targetSessionId;
        private final long targetGeneration;
        private final long expiresAtMs;
        private final Channel channel;

        private Entry(String queryId, String callerInstance, String callerSessionId,
                      long callerGeneration, String targetInstance, String targetSessionId,
                      long targetGeneration, long expiresAtMs, Channel channel) {
            this.queryId = queryId;
            this.callerInstance = callerInstance;
            this.callerSessionId = callerSessionId;
            this.callerGeneration = callerGeneration;
            this.targetInstance = targetInstance;
            this.targetSessionId = targetSessionId;
            this.targetGeneration = targetGeneration;
            this.expiresAtMs = expiresAtMs;
            this.channel = channel;
        }
    }

    private static final class Tombstone {
        private final String callerSessionId;
        private final long callerGeneration;
        private final String targetSessionId;
        private final long targetGeneration;
        private final long expiresAtMs;

        private Tombstone(String callerSessionId, long callerGeneration,
                          String targetSessionId, long targetGeneration, long expiresAtMs) {
            this.callerSessionId = callerSessionId;
            this.callerGeneration = callerGeneration;
            this.targetSessionId = targetSessionId;
            this.targetGeneration = targetGeneration;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Tombstone> preCancelled = new LinkedHashMap<>();
    private final Map<String, Long> terminal = new LinkedHashMap<>();
    private final java.util.function.LongSupplier clock;

    public BrokerProviderQueryCancellation() {
        this(android.os.SystemClock::elapsedRealtime);
    }

    public BrokerProviderQueryCancellation(java.util.function.LongSupplier clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public synchronized Handle open(String queryId, String callerInstance, String callerSessionId,
                                    long callerGeneration, String targetInstance,
                                    String targetSessionId, long targetGeneration) {
        long now = clock.getAsLong();
        purgeLocked(now);
        requireText(queryId, "queryId");
        requireText(callerInstance, "callerInstance");
        requireText(callerSessionId, "callerSessionId");
        requireText(targetInstance, "targetInstance");
        requireText(targetSessionId, "targetSessionId");
        if (callerGeneration < 1L || targetGeneration < 1L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (entries.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("PROVIDER_QUERY_CANCELLATION_CAPACITY_EXHAUSTED");
        }
        if (entries.containsKey(queryId) || terminal.containsKey(queryId)) {
            throw new SecurityException("PROVIDER_QUERY_ID_REUSED");
        }
        Channel channel = new Channel();
        Entry entry = new Entry(queryId, callerInstance, callerSessionId, callerGeneration,
                targetInstance, targetSessionId, targetGeneration,
                Math.addExact(now, ENTRY_TTL_MS), channel);
        entries.put(queryId, entry);
        Tombstone cancelled = preCancelled.remove(queryId);
        if (cancelled != null) {
            validate(cancelled.callerSessionId, cancelled.callerGeneration,
                    cancelled.targetSessionId, cancelled.targetGeneration,
                    callerSessionId, callerGeneration, targetSessionId, targetGeneration);
            channel.cancelFromBroker();
        }
        return new Handle(queryId, channel);
    }

    /** Request cancellation and return a stable status for diagnostics and tests. */
    public String cancel(String queryId, String callerSessionId, long callerGeneration,
                         String targetSessionId, long targetGeneration) {
        Entry entry;
        Channel channel = null;
        String status;
        synchronized (this) {
            long now = clock.getAsLong();
            purgeLocked(now);
            requireText(queryId, "queryId");
            entry = entries.get(queryId);
            if (entry != null) {
                validate(entry.callerSessionId, entry.callerGeneration,
                        entry.targetSessionId, entry.targetGeneration,
                        callerSessionId, callerGeneration, targetSessionId, targetGeneration);
                channel = entry.channel;
                status = channel.cancelled() ? "PROVIDER_QUERY_CANCEL_ALREADY_REQUESTED"
                        : "PROVIDER_QUERY_CANCEL_REQUESTED";
            } else if (terminal.containsKey(queryId)) {
                status = "PROVIDER_QUERY_ALREADY_TERMINAL";
            } else {
                Tombstone existing = preCancelled.get(queryId);
                if (existing != null) {
                    validate(existing.callerSessionId, existing.callerGeneration,
                            existing.targetSessionId, existing.targetGeneration,
                            callerSessionId, callerGeneration, targetSessionId, targetGeneration);
                    status = "PROVIDER_QUERY_CANCEL_ALREADY_PENDING";
                } else {
                    requireText(callerSessionId, "callerSessionId");
                    requireText(targetSessionId, "targetSessionId");
                    if (callerGeneration < 1L || targetGeneration < 1L) {
                        throw new IllegalArgumentException("generation must be positive");
                    }
                    if (preCancelled.size() >= MAX_ENTRIES) {
                        throw new IllegalStateException(
                                "PROVIDER_QUERY_CANCELLATION_CAPACITY_EXHAUSTED");
                    }
                    preCancelled.put(queryId, new Tombstone(callerSessionId, callerGeneration,
                            targetSessionId, targetGeneration, Math.addExact(now, ENTRY_TTL_MS)));
                    status = "PROVIDER_QUERY_CANCEL_PENDING";
                }
            }
        }
        if (channel != null) channel.cancelFromBroker();
        return status;
    }

    public synchronized void close(String queryId) {
        if (queryId == null || queryId.trim().isEmpty()) return;
        Entry entry = entries.remove(queryId);
        preCancelled.remove(queryId);
        recordTerminalLocked(queryId, Math.addExact(clock.getAsLong(), ENTRY_TTL_MS));
        if (entry != null) entry.channel.closeFromBroker();
    }

    public synchronized int invalidateSession(String sessionId, long generation) {
        if (sessionId == null || sessionId.trim().isEmpty()) return 0;
        List<String> removed = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if ((entry.callerSessionId.equals(sessionId) && entry.callerGeneration == generation)
                    || (entry.targetSessionId.equals(sessionId)
                    && entry.targetGeneration == generation)) {
                removed.add(entry.queryId);
            }
        }
        for (String queryId : removed) {
            Entry entry = entries.remove(queryId);
            if (entry != null) entry.channel.closeFromBroker();
            recordTerminalLocked(queryId, Math.addExact(clock.getAsLong(), ENTRY_TTL_MS));
        }
        preCancelled.entrySet().removeIf(value ->
                (value.getValue().callerSessionId.equals(sessionId)
                        && value.getValue().callerGeneration == generation)
                        || (value.getValue().targetSessionId.equals(sessionId)
                        && value.getValue().targetGeneration == generation));
        return removed.size();
    }

    public synchronized int invalidateInstance(String instanceId) {
        if (instanceId == null || instanceId.trim().isEmpty()) return 0;
        List<String> removed = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.callerInstance.equals(instanceId) || entry.targetInstance.equals(instanceId)) {
                removed.add(entry.queryId);
            }
        }
        for (String queryId : removed) {
            Entry entry = entries.remove(queryId);
            if (entry != null) entry.channel.closeFromBroker();
            recordTerminalLocked(queryId, Math.addExact(clock.getAsLong(), ENTRY_TTL_MS));
        }
        return removed.size();
    }

    public synchronized int purgeExpired() {
        long now = clock.getAsLong();
        return purgeLocked(now);
    }

    public synchronized int size() {
        purgeLocked(clock.getAsLong());
        return entries.size() + preCancelled.size();
    }

    private int purgeLocked(long now) {
        int removed = 0;
        List<String> expired = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.expiresAtMs <= now) expired.add(entry.queryId);
        }
        for (String queryId : expired) {
            Entry entry = entries.remove(queryId);
            if (entry != null) {
                entry.channel.cancelFromBroker();
                entry.channel.closeFromBroker();
                recordTerminalLocked(queryId, Math.addExact(now, ENTRY_TTL_MS));
                removed++;
            }
        }
        List<String> expiredTombstones = new ArrayList<>();
        for (Map.Entry<String, Tombstone> value : preCancelled.entrySet()) {
            if (value.getValue().expiresAtMs <= now) expiredTombstones.add(value.getKey());
        }
        for (String queryId : expiredTombstones) {
            if (preCancelled.remove(queryId) != null) removed++;
        }
        List<String> expiredTerminal = new ArrayList<>();
        for (Map.Entry<String, Long> value : terminal.entrySet()) {
            if (value.getValue() <= now) expiredTerminal.add(value.getKey());
        }
        for (String queryId : expiredTerminal) {
            if (terminal.remove(queryId) != null) removed++;
        }
        return removed;
    }

    private void recordTerminalLocked(String queryId, long expiresAtMs) {
        terminal.put(queryId, expiresAtMs);
        while (terminal.size() > MAX_TERMINAL_ENTRIES) {
            String oldest = terminal.keySet().iterator().next();
            terminal.remove(oldest);
        }
    }

    private static void validate(String expectedCallerSession, long expectedCallerGeneration,
                                 String expectedTargetSession, long expectedTargetGeneration,
                                 String callerSession, long callerGeneration,
                                 String targetSession, long targetGeneration) {
        if (!expectedCallerSession.equals(callerSession)
                || expectedCallerGeneration != callerGeneration) {
            throw new SecurityException("PROVIDER_QUERY_CANCEL_CALLER_SESSION_MISMATCH");
        }
        if (!expectedTargetSession.equals(targetSession)
                || expectedTargetGeneration != targetGeneration) {
            throw new SecurityException("PROVIDER_QUERY_CANCEL_TARGET_SESSION_MISMATCH");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static final class Channel extends IProviderQueryCancellation.Stub {
        private IProviderQueryCancellation endpoint;
        private boolean cancelled;
        private boolean closed;

        @Override public void attach(IProviderQueryCancellation value) throws RemoteException {
            if (value == null) throw new IllegalArgumentException("cancellation endpoint is required");
            boolean cancelNow;
            synchronized (this) {
                if (closed) return;
                endpoint = value;
                cancelNow = cancelled;
            }
            if (cancelNow) cancelEndpoint(value);
        }

        @Override public void cancel() throws RemoteException {
            cancelFromBroker();
        }

        @Override public void detach() {
            synchronized (this) { endpoint = null; }
        }

        synchronized boolean cancelled() { return cancelled; }

        void cancelFromBroker() {
            IProviderQueryCancellation value;
            synchronized (this) {
                if (closed) return;
                cancelled = true;
                value = endpoint;
            }
            if (value != null) cancelEndpoint(value);
        }

        synchronized void closeFromBroker() {
            closed = true;
            endpoint = null;
        }

        private static void cancelEndpoint(IProviderQueryCancellation value) {
            try { value.cancel(); }
            catch (RemoteException ignored) { }
            catch (RuntimeException ignored) { }
        }
    }
}
