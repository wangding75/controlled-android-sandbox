package com.warden.controlledsandbox.framework.routing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Generation-scoped identity and lifecycle model for Guest PendingIntent senders. */
public final class VirtualPendingIntentRegistry implements AutoCloseable {
    public static final int FLAG_ONE_SHOT = 0x40000000;
    public static final int FLAG_NO_CREATE = 0x20000000;
    public static final int FLAG_CANCEL_CURRENT = 0x10000000;
    public static final int FLAG_UPDATE_CURRENT = 0x08000000;
    public static final int FLAG_IMMUTABLE = 0x04000000;

    public enum Kind { BROADCAST, ACTIVITY, ACTIVITY_RESULT, SERVICE, FOREGROUND_SERVICE }

    @FunctionalInterface public interface Delivery {
        int deliver(Record record, Object fillInPayload) throws Exception;
    }

    public record Spec(Kind kind, int requestCode, String action, String component,
                       String data, int flags) {
        public Spec {
            kind = Objects.requireNonNull(kind, "kind");
            action = normalize(action); component = normalize(component); data = normalize(data);
        }
        Key key() { return new Key(kind, requestCode, action, component, data); }
        public boolean oneShot() { return (flags & FLAG_ONE_SHOT) != 0; }
        public boolean immutable() { return (flags & FLAG_IMMUTABLE) != 0; }
    }

    public static final class Record {
        private final long id;
        private final String packageName;
        private final int virtualUserId;
        private final long generation;
        private final Object token;
        private Spec spec;
        private Object payload;
        private boolean cancelled;
        private int sends;

        private Record(long id, String packageName, int virtualUserId, long generation,
                       Object token, Spec spec, Object payload) {
            this.id = id; this.packageName = packageName; this.virtualUserId = virtualUserId;
            this.generation = generation; this.token = token; this.spec = spec; this.payload = payload;
        }
        public long id() { return id; }
        public String packageName() { return packageName; }
        public int virtualUserId() { return virtualUserId; }
        public long generation() { return generation; }
        public Object token() { return token; }
        public synchronized Spec spec() { return spec; }
        public synchronized Object payload() { return payload; }
        public synchronized boolean cancelled() { return cancelled; }
        public synchronized int sends() { return sends; }
        private synchronized void update(Spec value, Object nextPayload) { spec = value; payload = nextPayload; }
        private synchronized void cancel() { cancelled = true; }
        private synchronized void sent() { sends++; }
    }

    public record IssueResult(Record record, boolean created) { }
    public record Snapshot(int active, int keys, long nextId) { }

    private final String packageName;
    private final int virtualUserId;
    private final long generation;
    private final Delivery delivery;
    private final AtomicLong ids = new AtomicLong(1L);
    private final Map<Key, Record> byKey = new LinkedHashMap<>();
    private final Map<Object, Record> byToken = new LinkedHashMap<>();

    public VirtualPendingIntentRegistry(String packageName, int virtualUserId, long generation,
                                        Delivery delivery) {
        if (packageName == null || packageName.trim().isEmpty() || virtualUserId < 0 || generation < 1) {
            throw new IllegalArgumentException("PendingIntent owner identity is invalid");
        }
        this.packageName = packageName.trim();
        this.virtualUserId = virtualUserId;
        this.generation = generation;
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    public synchronized IssueResult issue(Spec spec, Object token, Object payload) {
        Objects.requireNonNull(spec, "spec"); Objects.requireNonNull(token, "token");
        Key key = spec.key();
        Record existing = byKey.get(key);
        if ((spec.flags() & FLAG_NO_CREATE) != 0) return new IssueResult(existing, false);
        if (existing != null && (spec.flags() & FLAG_CANCEL_CURRENT) != 0) {
            cancelInternal(existing); existing = null;
        }
        if (existing != null) {
            if ((spec.flags() & FLAG_UPDATE_CURRENT) != 0) existing.update(spec, payload);
            return new IssueResult(existing, false);
        }
        Record record = new Record(ids.getAndIncrement(), packageName, virtualUserId, generation,
                token, spec, payload);
        byKey.put(key, record); byToken.put(token, record);
        return new IssueResult(record, true);
    }

    public synchronized Record find(Object token) { return byToken.get(token); }

    public int send(Object token, Object fillInPayload) throws Exception {
        Record record;
        synchronized (this) {
            record = byToken.get(token);
            if (record == null || record.cancelled()) throw new IllegalStateException("VIRTUAL_PENDING_INTENT_CANCELLED");
            if (record.spec().immutable() && fillInPayload != null) {
                throw new SecurityException("VIRTUAL_PENDING_INTENT_IMMUTABLE");
            }
            record.sent();
        }
        try { return delivery.deliver(record, fillInPayload); }
        finally { if (record.spec().oneShot()) cancel(token); }
    }

    public synchronized boolean cancel(Object token) {
        Record record = byToken.get(token); return record != null && cancelInternal(record);
    }

    public synchronized int cancelAll() {
        int count = byToken.size();
        for (Record record : byToken.values()) record.cancel();
        byToken.clear(); byKey.clear(); return count;
    }

    public synchronized Snapshot snapshot() { return new Snapshot(byToken.size(), byKey.size(), ids.get()); }
    public synchronized Map<Long, Spec> specs() {
        Map<Long, Spec> out = new LinkedHashMap<>();
        for (Record record : byToken.values()) out.put(record.id(), record.spec());
        return Collections.unmodifiableMap(out);
    }
    @Override public void close() { cancelAll(); }

    private boolean cancelInternal(Record record) {
        if (record == null || record.cancelled()) return false;
        record.cancel(); byToken.remove(record.token()); byKey.remove(record.spec().key(), record); return true;
    }
    private record Key(Kind kind, int requestCode, String action, String component, String data) { }
    private static String normalize(String value) { return value == null ? "" : value.trim(); }
}
