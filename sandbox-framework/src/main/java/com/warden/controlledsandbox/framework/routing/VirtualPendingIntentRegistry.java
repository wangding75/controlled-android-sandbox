package com.warden.controlledsandbox.framework.routing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Durable identity and fail-closed send policy for Guest PendingIntent senders. */
public final class VirtualPendingIntentRegistry implements AutoCloseable {
    public static final int FLAG_ONE_SHOT = 0x40000000;
    public static final int FLAG_NO_CREATE = 0x20000000;
    public static final int FLAG_CANCEL_CURRENT = 0x10000000;
    public static final int FLAG_UPDATE_CURRENT = 0x08000000;
    public static final int FLAG_IMMUTABLE = 0x04000000;
    public static final int FLAG_MUTABLE = 0x02000000;

    public enum Kind { BROADCAST, ACTIVITY, ACTIVITY_RESULT, SERVICE, FOREGROUND_SERVICE }

    public record SendRequest(Object fillInPayload, int flagsMask, int flagsValues,
                              String senderPermission, int senderUid) {
        public SendRequest {
            senderPermission = normalize(senderPermission);
            if (senderUid < -1) throw new IllegalArgumentException("senderUid is invalid");
        }
        public static SendRequest simple(Object fillInPayload) {
            return new SendRequest(fillInPayload, 0, 0, "", -1);
        }
    }

    @FunctionalInterface public interface Delivery {
        int deliver(Record record, SendRequest request) throws Exception;
    }

    /** Persistence port kept independent from Binder/system-service implementation packages. */
    public interface Persistence {
        DurableRecord reserve(DurableRecord candidate, boolean noCreate,
                              boolean cancelCurrent, boolean updateCurrent);
        DurableRecord markSent(String tokenId);
        boolean cancel(String tokenId);
        java.util.List<DurableRecord> records();
    }

    public record DurableRecord(String tokenId, String kind, int requestCode, String action,
                                String component, String data, String filterIdentity, int flags,
                                String creatorPackage, int creatorUid, String requiredPermission,
                                String ownerProcessName, long ownerGeneration, String packageRevision,
                                Object payload, int sends, boolean cancelled, long updatedAtMs) {
        public DurableRecord {
            tokenId = normalize(tokenId); kind = required(kind, "kind");
            action = normalize(action); component = normalize(component); data = normalize(data);
            filterIdentity = required(filterIdentity, "filterIdentity");
            creatorPackage = required(creatorPackage, "creatorPackage");
            requiredPermission = normalize(requiredPermission);
            ownerProcessName = required(ownerProcessName, "ownerProcessName");
            packageRevision = required(packageRevision, "packageRevision");
            if (requestCode < 0 || creatorUid < 0 || ownerGeneration < 0L
                    || sends < 0 || updatedAtMs < 0L) {
                throw new IllegalArgumentException("invalid durable PendingIntent record");
            }
        }
    }

    public record Spec(Kind kind, int requestCode, String action, String component,
                       String data, String filterIdentity, int flags, String requiredPermission) {
        public Spec {
            kind = Objects.requireNonNull(kind, "kind");
            if (requestCode < 0) throw new IllegalArgumentException("requestCode must be non-negative");
            action = normalize(action); component = normalize(component); data = normalize(data);
            filterIdentity = required(filterIdentity, "filterIdentity");
            requiredPermission = normalize(requiredPermission);
            if ((flags & FLAG_IMMUTABLE) != 0 && (flags & FLAG_MUTABLE) != 0) {
                throw new IllegalArgumentException("PendingIntent cannot be mutable and immutable");
            }
        }
        public Spec(Kind kind, int requestCode, String action, String component,
                    String data, int flags) {
            this(kind, requestCode, action, component, data,
                    defaultFilterIdentity(action, component, data), flags, "");
        }
        public Spec(Kind kind, int requestCode, String action, String component,
                    String data, int flags, String requiredPermission) {
            this(kind, requestCode, action, component, data,
                    defaultFilterIdentity(action, component, data), flags, requiredPermission);
        }
        Key key() { return new Key(kind, requestCode, filterIdentity); }
        public boolean oneShot() { return (flags & FLAG_ONE_SHOT) != 0; }
        public boolean immutable() { return (flags & FLAG_IMMUTABLE) != 0; }
        public boolean mutable() { return (flags & FLAG_MUTABLE) != 0; }
    }

    public static final class Record {
        private final long id;
        private final String persistentTokenId;
        private final String packageName;
        private final int virtualUserId;
        private final int creatorUid;
        private final long generation;
        private final Object token;
        private Spec spec;
        private Object payload;
        private boolean cancelled;
        private int sends;

        private Record(long id, String persistentTokenId, String packageName, int virtualUserId,
                       int creatorUid, long generation, Object token, Spec spec, Object payload,
                       int sends, boolean cancelled) {
            this.id = id; this.persistentTokenId = required(persistentTokenId, "persistentTokenId");
            this.packageName = packageName; this.virtualUserId = virtualUserId;
            this.creatorUid = creatorUid; this.generation = generation; this.token = token;
            this.spec = spec; this.payload = payload; this.sends = sends; this.cancelled = cancelled;
        }
        public long id() { return id; }
        public String persistentTokenId() { return persistentTokenId; }
        public String packageName() { return packageName; }
        public int virtualUserId() { return virtualUserId; }
        public int creatorUid() { return creatorUid; }
        public long generation() { return generation; }
        public Object token() { return token; }
        public synchronized Spec spec() { return spec; }
        public synchronized Object payload() { return payload; }
        public synchronized boolean cancelled() { return cancelled; }
        public synchronized int sends() { return sends; }
        private synchronized void update(Spec value, Object nextPayload) { spec = value; payload = nextPayload; }
        private synchronized void cancel() { cancelled = true; }
        private synchronized void sent(int count, boolean isCancelled) { sends = count; cancelled = isCancelled; }
    }

    public record IssueResult(Record record, boolean created) { }
    public record Snapshot(int active, int keys, long nextId, int persistent) {
        public Snapshot(int active, int keys, long nextId) { this(active, keys, nextId, 0); }
    }

    private final String packageName;
    private final int virtualUserId;
    private final int creatorUid;
    private final long generation;
    private final String processName;
    private final String packageRevision;
    private final Delivery delivery;
    private final Persistence persistence;
    private final AtomicLong ids = new AtomicLong(1L);
    private final Map<Key, Record> byKey = new LinkedHashMap<>();
    private final Map<Object, Record> byToken = new LinkedHashMap<>();
    private final Map<String, Record> byPersistentId = new LinkedHashMap<>();

    public VirtualPendingIntentRegistry(String packageName, int virtualUserId, long generation,
                                        Delivery delivery) {
        this(packageName, virtualUserId, 0, generation, packageName, "legacy-revision",
                null, delivery);
    }

    public VirtualPendingIntentRegistry(String packageName, int virtualUserId, int creatorUid,
            long generation, String processName, String packageRevision,
            Persistence persistence, Delivery delivery) {
        if (packageName == null || packageName.trim().isEmpty() || virtualUserId < 0
                || creatorUid < 0 || generation < 1) {
            throw new IllegalArgumentException("PendingIntent owner identity is invalid");
        }
        this.packageName = packageName.trim(); this.virtualUserId = virtualUserId;
        this.creatorUid = creatorUid; this.generation = generation;
        this.processName = required(processName, "processName");
        this.packageRevision = required(packageRevision, "packageRevision");
        this.persistence = persistence;
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    public synchronized IssueResult issue(Spec spec, Object token, Object payload) {
        Objects.requireNonNull(spec, "spec"); Objects.requireNonNull(token, "token");
        Key key = spec.key(); Record local = byKey.get(key);
        if (persistence == null) return issueLocal(spec, token, payload, local);
        DurableRecord candidate =
                new DurableRecord("", spec.kind().name(),
                        spec.requestCode(), spec.action(), spec.component(), spec.data(), spec.filterIdentity(), spec.flags(),
                        packageName, creatorUid, spec.requiredPermission(), processName, generation,
                        packageRevision, payload, 0, false, System.currentTimeMillis());
        java.util.Set<String> beforeTokens = new java.util.LinkedHashSet<>();
        for (DurableRecord value : persistence.records()) {
            beforeTokens.add(value.tokenId());
        }
        DurableRecord persisted = persistence.reserve(candidate,
                (spec.flags() & FLAG_NO_CREATE) != 0, (spec.flags() & FLAG_CANCEL_CURRENT) != 0,
                (spec.flags() & FLAG_UPDATE_CURRENT) != 0);
        if (persisted == null) return new IssueResult(null, false);
        boolean newlyCreated = !beforeTokens.contains(persisted.tokenId());
        Record existing = byPersistentId.get(persisted.tokenId());
        if (existing != null) {
            existing.update(specFrom(persisted), persisted.payload());
            return new IssueResult(existing, false);
        }
        if (local != null && !local.persistentTokenId().equals(persisted.tokenId())) cancelLocal(local);
        Record created = new Record(ids.getAndIncrement(), persisted.tokenId(), packageName,
                virtualUserId, creatorUid, generation, token, specFrom(persisted), persisted.payload(),
                persisted.sends(), persisted.cancelled());
        byKey.put(created.spec().key(), created); byToken.put(token, created);
        byPersistentId.put(created.persistentTokenId(), created);
        return new IssueResult(created, newlyCreated);
    }

    private IssueResult issueLocal(Spec spec, Object token, Object payload, Record existing) {
        if ((spec.flags() & FLAG_NO_CREATE) != 0) return new IssueResult(existing, false);
        if (existing != null && (spec.flags() & FLAG_CANCEL_CURRENT) != 0) {
            cancelLocal(existing); existing = null;
        }
        if (existing != null) {
            if ((spec.flags() & FLAG_UPDATE_CURRENT) != 0) existing.update(spec, payload);
            return new IssueResult(existing, false);
        }
        String persistent = "local-" + ids.get();
        Record record = new Record(ids.getAndIncrement(), persistent, packageName, virtualUserId,
                creatorUid, generation, token, spec, payload, 0, false);
        byKey.put(spec.key(), record); byToken.put(token, record); byPersistentId.put(persistent, record);
        return new IssueResult(record, true);
    }

    public synchronized Record find(Object token) { return byToken.get(token); }
    public synchronized boolean equivalent(Object first, Object second) {
        Record left = byToken.get(first); Record right = byToken.get(second);
        return left != null && right != null && left.persistentTokenId().equals(right.persistentTokenId());
    }

    public int send(Object token, Object fillInPayload) throws Exception {
        return send(token, SendRequest.simple(fillInPayload));
    }

    public int send(Object token, SendRequest request) throws Exception {
        Record record;
        synchronized (this) {
            record = byToken.get(token);
            if (record == null || record.cancelled()) throw new IllegalStateException("VIRTUAL_PENDING_INTENT_CANCELLED");
            validateSend(record, request == null ? SendRequest.simple(null) : request);
        }
        SendRequest resolved = request == null ? SendRequest.simple(null) : request;
        try { return delivery.deliver(record, resolved); }
        finally {
            synchronized (this) {
                if (persistence != null) {
                    DurableRecord updated =
                            persistence.markSent(record.persistentTokenId());
                    if (updated != null) record.sent(updated.sends(), updated.cancelled());
                } else {
                    record.sent(record.sends() + 1, record.spec().oneShot());
                }
                if (record.cancelled() || record.spec().oneShot()) cancelLocal(record);
            }
        }
    }

    private static void validateSend(Record record, SendRequest request) {
        Spec spec = record.spec();
        boolean mutatingFillIn = request.fillInPayload() != null
                || request.flagsMask() != 0 || request.flagsValues() != 0;
        if (spec.immutable() && mutatingFillIn) {
            throw new SecurityException("VIRTUAL_PENDING_INTENT_IMMUTABLE");
        }
        if (!spec.requiredPermission().isEmpty()
                && !spec.requiredPermission().equals(request.senderPermission())) {
            throw new SecurityException("VIRTUAL_PENDING_INTENT_SENDER_PERMISSION_DENIED");
        }
    }

    public synchronized boolean cancel(Object token) {
        Record record = byToken.get(token);
        if (record == null) return false;
        boolean remote = persistence == null || persistence.cancel(record.persistentTokenId());
        cancelLocal(record); return remote;
    }

    public synchronized int cancelAll() {
        int count = byToken.size();
        for (Record record : byToken.values().toArray(new Record[0])) {
            if (persistence != null) persistence.cancel(record.persistentTokenId());
            cancelLocal(record);
        }
        return count;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(byToken.size(), byKey.size(), ids.get(), byPersistentId.size());
    }
    public synchronized Map<Long, Spec> specs() {
        Map<Long, Spec> out = new LinkedHashMap<>();
        for (Record record : byToken.values()) out.put(record.id(), record.spec());
        return Collections.unmodifiableMap(out);
    }

    /** Process shutdown detaches local Binder handles without cancelling durable senders. */
    @Override public synchronized void close() {
        for (Record record : byToken.values()) record.cancel();
        byToken.clear(); byKey.clear(); byPersistentId.clear();
    }

    private void cancelLocal(Record record) {
        if (record == null) return;
        record.cancel(); byToken.remove(record.token()); byKey.remove(record.spec().key(), record);
        byPersistentId.remove(record.persistentTokenId(), record);
    }
    private static Spec specFrom(DurableRecord value) {
        return new Spec(Kind.valueOf(value.kind()), value.requestCode(), value.action(),
                value.component(), value.data(), value.filterIdentity(), value.flags(), value.requiredPermission());
    }
    private record Key(Kind kind, int requestCode, String filterIdentity) { }
    private static String defaultFilterIdentity(String action, String component, String data) {
        return "a=" + normalize(action) + "|c=" + normalize(component) + "|d=" + normalize(data);
    }
    private static String normalize(String value) { return value == null ? "" : value.trim(); }
    private static String required(String value, String name) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
