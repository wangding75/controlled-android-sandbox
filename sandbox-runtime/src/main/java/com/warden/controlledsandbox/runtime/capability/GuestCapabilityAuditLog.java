package com.warden.controlledsandbox.runtime.capability;

import com.warden.controlledsandbox.framework.capability.CapabilityAuditEvent;
import com.warden.controlledsandbox.framework.capability.CapabilityAuditSink;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded per-Guest-generation capability audit ledger. */
public final class GuestCapabilityAuditLog implements CapabilityAuditSink {
    private static final int DEFAULT_LIMIT = 128;
    private final int limit;
    private final AtomicLong sequence = new AtomicLong();
    private final Deque<CapabilityAuditEvent> events = new ArrayDeque<>();

    public GuestCapabilityAuditLog() { this(DEFAULT_LIMIT); }

    GuestCapabilityAuditLog(int limit) {
        if (limit < 1 || limit > 4096) throw new IllegalArgumentException("invalid audit limit");
        this.limit = limit;
    }

    public synchronized CapabilityAuditEvent event(String capability, String service, String operation,
                                                   String decision, String detail) {
        return append(capability, service, operation, decision, detail);
    }

    @Override public synchronized void record(CapabilityAuditEvent event) {
        if (event == null) return;
        append(event.capability(), event.service(), event.operation(), event.decision(), event.detail());
    }

    private CapabilityAuditEvent append(String capability, String service, String operation,
                                        String decision, String detail) {
        CapabilityAuditEvent stored = new CapabilityAuditEvent(sequence.incrementAndGet(), capability,
                service, operation, decision, detail);
        events.addLast(stored);
        while (events.size() > limit) events.removeFirst();
        return stored;
    }

    public synchronized List<CapabilityAuditEvent> snapshot() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized int size() { return events.size(); }

    public synchronized int deniedCount() {
        int count = 0;
        for (CapabilityAuditEvent event : events) {
            if (event.decision().contains("DENIED") || event.decision().contains("FAILED")) count++;
        }
        return count;
    }

    public synchronized ArrayList<String> compactSnapshot() {
        ArrayList<String> out = new ArrayList<>();
        for (CapabilityAuditEvent event : events) {
            out.add(event.sequence() + ":" + event.capability() + ":" + event.service()
                    + ":" + event.operation() + ":" + event.decision()
                    + (event.detail().isEmpty() ? "" : ":" + event.detail()));
        }
        return out;
    }
}
