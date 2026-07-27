package com.warden.controlledsandbox.domain.component.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ownership, sequencing and expiry model for process-local Provider cursor transports. */
public final class CursorLeaseRegistry {
    public static final class Lease {
        private final String token;
        private final String ownerSessionId;
        private final String providerInstanceId;
        private final List<String> columns;
        private final int rowCount;
        private final long generation;
        private final long expiresAtMs;
        private final int nextOffset;
        private final long nextSequence;
        private final boolean endReached;

        private Lease(MutableLease source) {
            this.token = source.token;
            this.ownerSessionId = source.ownerSessionId;
            this.providerInstanceId = source.providerInstanceId;
            this.columns = Collections.unmodifiableList(new ArrayList<>(source.columns));
            this.rowCount = source.rowCount;
            this.generation = source.generation;
            this.expiresAtMs = source.expiresAtMs;
            this.nextOffset = source.nextOffset;
            this.nextSequence = source.nextSequence;
            this.endReached = source.endReached;
        }

        public String token() { return token; }
        public String ownerSessionId() { return ownerSessionId; }
        public String providerInstanceId() { return providerInstanceId; }
        public List<String> columns() { return columns; }
        public int rowCount() { return rowCount; }
        public long generation() { return generation; }
        public long expiresAtMs() { return expiresAtMs; }
        public int nextOffset() { return nextOffset; }
        public long nextSequence() { return nextSequence; }
        public boolean endReached() { return endReached; }
    }

    private static final class MutableLease {
        final String token;
        final String ownerSessionId;
        final String providerInstanceId;
        final List<String> columns;
        final int rowCount;
        final long generation;
        final long expiresAtMs;
        int nextOffset;
        long nextSequence;
        boolean endReached;

        MutableLease(String token, String ownerSessionId, String providerInstanceId,
                     List<String> columns, int rowCount, long generation, long expiresAtMs) {
            this.token = token;
            this.ownerSessionId = ownerSessionId;
            this.providerInstanceId = providerInstanceId;
            this.columns = new ArrayList<>(columns);
            this.rowCount = rowCount;
            this.generation = generation;
            this.expiresAtMs = expiresAtMs;
            this.endReached = rowCount == 0;
        }
    }

    private final Map<String, MutableLease> leases = new LinkedHashMap<>();

    public synchronized Lease open(String ownerSessionId, String providerInstanceId,
                                   List<String> columns, int rowCount, long generation,
                                   long nowMs, long ttlMs) {
        return open(UUID.randomUUID().toString(), ownerSessionId, providerInstanceId, columns,
                rowCount, generation, nowMs, ttlMs, Integer.MAX_VALUE);
    }

    public synchronized Lease open(String token, String ownerSessionId, String providerInstanceId,
                                   List<String> columns, int rowCount, long generation,
                                   long nowMs, long ttlMs, int maxActiveLeases) {
        requireText(token, "token");
        requireText(ownerSessionId, "ownerSessionId");
        requireText(providerInstanceId, "providerInstanceId");
        if (columns == null) throw new IllegalArgumentException("columns is required");
        if (rowCount < 0) throw new IllegalArgumentException("rowCount must be non-negative");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if (ttlMs < 1) throw new IllegalArgumentException("ttlMs must be positive");
        if (maxActiveLeases < 1) throw new IllegalArgumentException("maxActiveLeases must be positive");
        purgeExpiredTokens(nowMs);
        if (leases.size() >= maxActiveLeases) throw new IllegalStateException("CURSOR_LEASE_CAPACITY_EXHAUSTED");
        if (leases.containsKey(token)) throw new IllegalStateException("DUPLICATE_CURSOR_LEASE");
        MutableLease lease = new MutableLease(token, ownerSessionId, providerInstanceId,
                columns, rowCount, generation, Math.addExact(nowMs, ttlMs));
        leases.put(token, lease);
        return new Lease(lease);
    }

    public synchronized Lease require(String token, String ownerSessionId, long generation, long nowMs) {
        purgeExpiredTokens(nowMs);
        MutableLease lease = requiredLease(token, ownerSessionId, generation);
        return new Lease(lease);
    }

    public synchronized Lease requirePage(String token, String ownerSessionId, long generation,
                                          int offset, long sequence, long nowMs) {
        purgeExpiredTokens(nowMs);
        MutableLease lease = requiredLease(token, ownerSessionId, generation);
        if (lease.endReached) throw new IllegalStateException("CURSOR_END_REACHED");
        if (offset != lease.nextOffset) throw new SecurityException("CURSOR_OFFSET_REPLAY_OR_SKIP");
        if (sequence != lease.nextSequence) throw new SecurityException("CURSOR_SEQUENCE_REPLAY_OR_SKIP");
        return new Lease(lease);
    }

    public synchronized Lease commitPage(String token, String ownerSessionId, long generation,
                                         long sequence, int emittedRows, boolean endReached) {
        MutableLease lease = requiredLease(token, ownerSessionId, generation);
        if (sequence != lease.nextSequence) throw new SecurityException("CURSOR_SEQUENCE_CHANGED");
        if (emittedRows < 0 || lease.nextOffset + emittedRows > lease.rowCount) {
            throw new IllegalArgumentException("Invalid emitted row count");
        }
        if (emittedRows == 0 && !endReached) throw new IllegalStateException("CURSOR_NO_PROGRESS");
        lease.nextOffset += emittedRows;
        lease.nextSequence++;
        lease.endReached = endReached || lease.nextOffset >= lease.rowCount;
        return new Lease(lease);
    }

    public synchronized boolean close(String token, String ownerSessionId, long generation) {
        MutableLease lease = leases.get(token);
        if (lease == null) return false;
        validateOwner(lease, ownerSessionId, generation);
        leases.remove(token);
        return true;
    }

    /** Process-local emergency cleanup after an unrecoverable serialization or cursor failure. */
    public synchronized boolean forceClose(String token) {
        return leases.remove(token) != null;
    }

    public synchronized List<String> closeSessionTokens(String ownerSessionId, long generation) {
        List<String> tokens = new ArrayList<>();
        for (MutableLease lease : leases.values()) {
            if (lease.ownerSessionId.equals(ownerSessionId) && lease.generation == generation) tokens.add(lease.token);
        }
        removeTokens(tokens);
        return Collections.unmodifiableList(tokens);
    }

    public synchronized int closeSession(String ownerSessionId, long generation) {
        return closeSessionTokens(ownerSessionId, generation).size();
    }

    public synchronized List<String> closeProviderTokens(String providerInstanceId) {
        List<String> tokens = new ArrayList<>();
        for (MutableLease lease : leases.values()) {
            if (lease.providerInstanceId.equals(providerInstanceId)) tokens.add(lease.token);
        }
        removeTokens(tokens);
        return Collections.unmodifiableList(tokens);
    }

    public synchronized int closeProvider(String providerInstanceId) {
        return closeProviderTokens(providerInstanceId).size();
    }

    public synchronized List<String> closeAllTokens() {
        List<String> tokens = new ArrayList<>(leases.keySet());
        leases.clear();
        return Collections.unmodifiableList(tokens);
    }

    public synchronized List<String> purgeExpiredTokens(long nowMs) {
        List<String> expired = new ArrayList<>();
        for (MutableLease lease : leases.values()) {
            if (lease.expiresAtMs <= nowMs) expired.add(lease.token);
        }
        removeTokens(expired);
        return Collections.unmodifiableList(expired);
    }

    public synchronized int size(long nowMs) {
        purgeExpiredTokens(nowMs);
        return leases.size();
    }

    private MutableLease requiredLease(String token, String ownerSessionId, long generation) {
        MutableLease lease = leases.get(token);
        if (lease == null) throw new IllegalArgumentException("UNKNOWN_CURSOR_LEASE");
        validateOwner(lease, ownerSessionId, generation);
        return lease;
    }

    private static void validateOwner(MutableLease lease, String ownerSessionId, long generation) {
        if (!lease.ownerSessionId.equals(ownerSessionId)) throw new SecurityException("CURSOR_OWNER_MISMATCH");
        if (lease.generation != generation) throw new SecurityException("CURSOR_GENERATION_MISMATCH");
    }

    private void removeTokens(List<String> tokens) {
        for (String token : tokens) leases.remove(token);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
