package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Broker-owned Cursor lease authority. Tokens, caller identity and page sequencing are never Guest-defined. */
public final class BrokerCursorRuntime {
    public static final long LEASE_TTL_MS = 120_000L;
    static final int MAX_ACTIVE_LEASES = 128;
    static final int MAX_PAGE_SIZE = 256;
    static final int MAX_TOTAL_ROWS = 1_000_000;

    public static final class Lease {
        private final String token;
        private final String callerInstance;
        private final String callerSessionId;
        private final long callerGeneration;
        private final String targetInstance;
        private final String targetPackage;
        private final int targetVirtualUserId;
        private final String targetProcessName;
        private final String targetSessionId;
        private final long targetGeneration;
        private final String uri;
        private final int flags;
        private final long expiresAtMs;
        private final int rowCount;
        private final int nextOffset;
        private final long nextSequence;
        private final boolean endReached;

        private Lease(MutableLease source) {
            this.token = source.token;
            this.callerInstance = source.callerInstance;
            this.callerSessionId = source.callerSessionId;
            this.callerGeneration = source.callerGeneration;
            this.targetInstance = source.targetInstance;
            this.targetPackage = source.targetPackage;
            this.targetVirtualUserId = source.targetVirtualUserId;
            this.targetProcessName = source.targetProcessName;
            this.targetSessionId = source.targetSessionId;
            this.targetGeneration = source.targetGeneration;
            this.uri = source.uri;
            this.flags = source.flags;
            this.expiresAtMs = source.expiresAtMs;
            this.rowCount = source.rowCount;
            this.nextOffset = source.nextOffset;
            this.nextSequence = source.nextSequence;
            this.endReached = source.endReached;
        }

        public String token() { return token; }
        public String callerInstance() { return callerInstance; }
        public String callerSessionId() { return callerSessionId; }
        public long callerGeneration() { return callerGeneration; }
        public String targetInstance() { return targetInstance; }
        public String targetPackage() { return targetPackage; }
        public int targetVirtualUserId() { return targetVirtualUserId; }
        public String targetProcessName() { return targetProcessName; }
        public String targetSessionId() { return targetSessionId; }
        public long targetGeneration() { return targetGeneration; }
        String uri() { return uri; }
        int flags() { return flags; }
        public long expiresAtMs() { return expiresAtMs; }
        int rowCount() { return rowCount; }
        int nextOffset() { return nextOffset; }
        long nextSequence() { return nextSequence; }
        boolean endReached() { return endReached; }
    }

    public static final class QueryReservation {
        private final String token;
        private QueryReservation(String token) { this.token = token; }
        public String token() { return token; }
    }

    public static final class PageReservation {
        private final String token;
        private final long sequence;
        private final int offset;
        private final int limit;
        private PageReservation(String token, long sequence, int offset, int limit) {
            this.token = token;
            this.sequence = sequence;
            this.offset = offset;
            this.limit = limit;
        }
        public String token() { return token; }
    }

    public static final class TerminalReservation {
        private final String token;
        private TerminalReservation(String token) { this.token = token; }
        String token() { return token; }
    }

    private static final class MutableLease {
        final String token;
        final String callerInstance;
        final String callerSessionId;
        final long callerGeneration;
        final String targetInstance;
        final String targetPackage;
        final int targetVirtualUserId;
        final String targetProcessName;
        final String targetSessionId;
        final long targetGeneration;
        final String uri;
        final int flags;
        final long expiresAtMs;
        boolean committed;
        boolean inFlight;
        int rowCount;
        int nextOffset;
        long nextSequence;
        boolean endReached;

        MutableLease(String token, String callerInstance, String callerSessionId, long callerGeneration,
                     String targetInstance, String targetPackage, int targetVirtualUserId,
                     String targetProcessName, String targetSessionId, long targetGeneration,
                     String uri, int flags, long expiresAtMs) {
            this.token = token;
            this.callerInstance = callerInstance;
            this.callerSessionId = callerSessionId;
            this.callerGeneration = callerGeneration;
            this.targetInstance = targetInstance;
            this.targetPackage = targetPackage;
            this.targetVirtualUserId = targetVirtualUserId;
            this.targetProcessName = targetProcessName;
            this.targetSessionId = targetSessionId;
            this.targetGeneration = targetGeneration;
            this.uri = uri;
            this.flags = flags;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final Map<String, MutableLease> leases = new LinkedHashMap<>();

    public synchronized QueryReservation reserveQuery(String callerInstance, String callerSessionId,
                                                long callerGeneration, String targetInstance,
                                                String targetPackage, int targetVirtualUserId,
                                                String targetProcessName, String targetSessionId,
                                                long targetGeneration, String uri, int flags, long nowMs) {
        requireText(callerInstance, "callerInstance");
        requireText(callerSessionId, "callerSessionId");
        requireText(targetInstance, "targetInstance");
        requireText(targetPackage, "targetPackage");
        requireText(targetProcessName, "targetProcessName");
        requireText(targetSessionId, "targetSessionId");
        requireText(uri, "uri");
        if (callerGeneration < 1 || targetGeneration < 1) throw new IllegalArgumentException("generation must be positive");
        if (targetVirtualUserId < 0) throw new IllegalArgumentException("targetVirtualUserId must be non-negative");
        if (flags <= 0) throw new IllegalArgumentException("flags must be positive");
        if (leases.size() >= MAX_ACTIVE_LEASES) throw new IllegalStateException("BROKER_CURSOR_CAPACITY_EXHAUSTED");
        String token;
        do { token = UUID.randomUUID().toString(); } while (leases.containsKey(token));
        MutableLease lease = new MutableLease(token, callerInstance, callerSessionId, callerGeneration,
                targetInstance, targetPackage, targetVirtualUserId, targetProcessName,
                targetSessionId, targetGeneration, uri, flags, Math.addExact(nowMs, LEASE_TTL_MS));
        leases.put(token, lease);
        return new QueryReservation(token);
    }

    public synchronized Lease commitQuery(QueryReservation reservation, Bundle result, long nowMs) {
        MutableLease lease = requireReservation(reservation);
        requireNotExpired(lease, nowMs);
        String resultToken = result == null ? "" : result.getString(RuntimeKeys.CURSOR_TOKEN, "");
        if (!lease.token.equals(resultToken)) throw new SecurityException("CURSOR_TOKEN_NOT_BROKER_ISSUED");
        int rowCount = result.getInt(RuntimeKeys.CURSOR_TOTAL_ROWS, -1);
        int nextOffset = result.getInt(RuntimeKeys.CURSOR_NEXT_OFFSET, -1);
        long nextSequence = result.getLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, -1);
        boolean endReached = result.getBoolean(RuntimeKeys.CURSOR_END_REACHED, false);
        if (rowCount < 0 || rowCount > MAX_TOTAL_ROWS) throw new IllegalArgumentException("CURSOR_ROW_LIMIT_EXCEEDED");
        if (nextOffset < 0 || nextOffset > rowCount) throw new IllegalArgumentException("INVALID_CURSOR_NEXT_OFFSET");
        if (nextSequence < 0 || nextSequence > 1) throw new IllegalArgumentException("INVALID_INITIAL_CURSOR_SEQUENCE");
        if (nextSequence == 0 && nextOffset != 0) throw new IllegalArgumentException("INVALID_INITIAL_CURSOR_STATE");
        if (endReached != (nextOffset >= rowCount)) throw new IllegalArgumentException("CURSOR_END_STATE_MISMATCH");
        lease.rowCount = rowCount;
        lease.nextOffset = nextOffset;
        lease.nextSequence = nextSequence;
        lease.endReached = endReached;
        lease.committed = true;
        return new Lease(lease);
    }

    public synchronized void rollbackQuery(QueryReservation reservation) {
        if (reservation == null) return;
        MutableLease lease = leases.get(reservation.token);
        if (lease != null && !lease.committed) leases.remove(reservation.token);
    }

    public synchronized Lease require(String token, long nowMs) {
        MutableLease lease = leases.get(token);
        if (lease == null || !lease.committed) throw new SecurityException("UNKNOWN_BROKER_CURSOR_LEASE");
        requireNotExpired(lease, nowMs);
        return new Lease(lease);
    }

    public synchronized PageReservation reservePage(String token, String callerSessionId, long callerGeneration,
                                             String targetSessionId, long targetGeneration,
                                             int offset, long sequence, int requestedLimit, long nowMs) {
        MutableLease lease = requireMutable(token, nowMs);
        validateSessions(lease, callerSessionId, callerGeneration, targetSessionId, targetGeneration);
        if (lease.inFlight) throw new IllegalStateException("CURSOR_OPERATION_IN_FLIGHT");
        if (lease.endReached) throw new IllegalStateException("CURSOR_END_REACHED");
        if (offset != lease.nextOffset) throw new SecurityException("CURSOR_OFFSET_REPLAY_OR_SKIP");
        if (sequence != lease.nextSequence) throw new SecurityException("CURSOR_SEQUENCE_REPLAY_OR_SKIP");
        int limit = normalizePageSize(requestedLimit);
        lease.inFlight = true;
        return new PageReservation(token, sequence, offset, limit);
    }

    public synchronized Lease commitPage(PageReservation reservation, Bundle result, long nowMs) {
        MutableLease lease = requirePageReservation(reservation, nowMs);
        int emitted = result == null ? -1 : result.getInt(RuntimeKeys.CURSOR_ROWS_RETURNED, -1);
        int nextOffset = result == null ? -1 : result.getInt(RuntimeKeys.CURSOR_NEXT_OFFSET, -1);
        long nextSequence = result == null ? -1 : result.getLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, -1);
        boolean endReached = result != null && result.getBoolean(RuntimeKeys.CURSOR_END_REACHED, false);
        if (emitted < 0 || emitted > reservation.limit) throw new IllegalArgumentException("INVALID_CURSOR_ROWS_RETURNED");
        if (nextOffset != reservation.offset + emitted || nextOffset > lease.rowCount) {
            throw new IllegalArgumentException("CURSOR_PAGE_OFFSET_MISMATCH");
        }
        if (nextSequence != reservation.sequence + 1) throw new IllegalArgumentException("CURSOR_PAGE_SEQUENCE_MISMATCH");
        if (emitted == 0 && !endReached) throw new IllegalStateException("CURSOR_NO_PROGRESS");
        if (endReached != (nextOffset >= lease.rowCount)) throw new IllegalArgumentException("CURSOR_END_STATE_MISMATCH");
        lease.nextOffset = nextOffset;
        lease.nextSequence = nextSequence;
        lease.endReached = endReached;
        lease.inFlight = false;
        return new Lease(lease);
    }

    synchronized void rollbackPage(PageReservation reservation) {
        if (reservation == null) return;
        MutableLease lease = leases.get(reservation.token);
        if (lease != null && lease.nextSequence == reservation.sequence) lease.inFlight = false;
    }

    public synchronized TerminalReservation reserveTerminal(String token, String callerSessionId,
                                                      long callerGeneration, String targetSessionId,
                                                      long targetGeneration, long sequence, long nowMs) {
        MutableLease lease = requireMutable(token, nowMs);
        validateSessions(lease, callerSessionId, callerGeneration, targetSessionId, targetGeneration);
        if (lease.inFlight) throw new IllegalStateException("CURSOR_OPERATION_IN_FLIGHT");
        if (sequence != lease.nextSequence) throw new SecurityException("CURSOR_SEQUENCE_REPLAY_OR_SKIP");
        lease.inFlight = true;
        return new TerminalReservation(token);
    }

    public synchronized Lease completeTerminal(TerminalReservation reservation) {
        if (reservation == null) throw new IllegalArgumentException("reservation is required");
        MutableLease lease = leases.remove(reservation.token);
        if (lease == null) throw new SecurityException("UNKNOWN_BROKER_CURSOR_LEASE");
        return new Lease(lease);
    }

    public synchronized Lease abort(String token) {
        MutableLease lease = leases.remove(token);
        return lease == null ? null : new Lease(lease);
    }

    synchronized List<Lease> invalidateSession(String sessionId, long generation) {
        List<Lease> removed = new ArrayList<>();
        for (MutableLease lease : new ArrayList<>(leases.values())) {
            if ((lease.callerSessionId.equals(sessionId) && lease.callerGeneration == generation)
                    || (lease.targetSessionId.equals(sessionId) && lease.targetGeneration == generation)) {
                leases.remove(lease.token);
                removed.add(new Lease(lease));
            }
        }
        return Collections.unmodifiableList(removed);
    }

    synchronized List<Lease> invalidateInstance(String instanceId) {
        List<Lease> removed = new ArrayList<>();
        for (MutableLease lease : new ArrayList<>(leases.values())) {
            if (lease.callerInstance.equals(instanceId) || lease.targetInstance.equals(instanceId)) {
                leases.remove(lease.token);
                removed.add(new Lease(lease));
            }
        }
        return Collections.unmodifiableList(removed);
    }

    synchronized List<Lease> purgeExpired(long nowMs) {
        List<Lease> removed = new ArrayList<>();
        for (MutableLease lease : new ArrayList<>(leases.values())) {
            if (lease.expiresAtMs <= nowMs) {
                leases.remove(lease.token);
                removed.add(new Lease(lease));
            }
        }
        return Collections.unmodifiableList(removed);
    }

    synchronized int size(long nowMs) {
        int committed = 0;
        for (MutableLease lease : leases.values()) if (lease.committed) committed++;
        return committed;
    }

    private MutableLease requireReservation(QueryReservation reservation) {
        if (reservation == null) throw new IllegalArgumentException("reservation is required");
        MutableLease lease = leases.get(reservation.token);
        if (lease == null || lease.committed) throw new IllegalStateException("CURSOR_QUERY_RESERVATION_MISSING");
        return lease;
    }

    private MutableLease requireMutable(String token, long nowMs) {
        MutableLease lease = leases.get(token);
        if (lease == null || !lease.committed) throw new SecurityException("UNKNOWN_BROKER_CURSOR_LEASE");
        requireNotExpired(lease, nowMs);
        return lease;
    }

    private MutableLease requirePageReservation(PageReservation reservation, long nowMs) {
        if (reservation == null) throw new IllegalArgumentException("reservation is required");
        MutableLease lease = requireMutable(reservation.token, nowMs);
        if (!lease.inFlight || lease.nextSequence != reservation.sequence || lease.nextOffset != reservation.offset) {
            throw new SecurityException("CURSOR_PAGE_RESERVATION_CHANGED");
        }
        return lease;
    }

    private static void validateSessions(MutableLease lease, String callerSessionId, long callerGeneration,
                                         String targetSessionId, long targetGeneration) {
        if (!lease.callerSessionId.equals(callerSessionId) || lease.callerGeneration != callerGeneration) {
            throw new SecurityException("CURSOR_CALLER_SESSION_MISMATCH");
        }
        if (!lease.targetSessionId.equals(targetSessionId) || lease.targetGeneration != targetGeneration) {
            throw new SecurityException("CURSOR_TARGET_SESSION_MISMATCH");
        }
    }

    private static int normalizePageSize(int requested) {
        if (requested < 1) throw new IllegalArgumentException("cursorPageSize must be positive");
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private static void requireNotExpired(MutableLease lease, long nowMs) {
        if (lease.expiresAtMs <= nowMs) throw new SecurityException("BROKER_CURSOR_LEASE_EXPIRED");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
