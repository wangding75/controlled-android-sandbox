package com.warden.controlledsandbox.framework.capability;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks callback/device resources so policy revocation can trigger best-effort release. */
public final class CapabilityLeaseRegistry implements AutoCloseable {
    public static final int MAX_ACTIVE_LEASES = 256;
    @FunctionalInterface public interface CleanupAction { void cleanup() throws Exception; }

    private final AtomicLong sequence = new AtomicLong();
    private final Map<Object, Lease> leases = new IdentityHashMap<>();

    public synchronized void register(String capability, Object token, CleanupAction cleanup) {
        if (token == null || cleanup == null) return;
        Lease previous = leases.remove(token);
        if (previous != null) cleanup(previous, CapabilityAuditSink.NO_OP, "TOKEN_REPLACED");
        if (leases.size() >= MAX_ACTIVE_LEASES) {
            try { cleanup.cleanup(); } catch (Exception ignored) { }
            throw new IllegalStateException("CAPABILITY_LEASE_LIMIT_EXCEEDED");
        }
        leases.put(token, new Lease(sequence.incrementAndGet(), capability, token, cleanup));
    }

    public synchronized boolean release(Object token, CapabilityAuditSink audit, String reason) {
        Lease lease = leases.remove(token);
        if (lease == null) return false;
        cleanup(lease, audit, reason == null ? "EXPLICIT_RELEASE" : reason);
        return true;
    }

    public synchronized int revokeDenied(CapabilityAccessPolicy policy, CapabilityAuditSink audit) {
        List<Lease> revoked = new ArrayList<>();
        for (Lease lease : leases.values()) {
            if (!policy.allowed(lease.capability)) revoked.add(lease);
        }
        for (Lease lease : revoked) leases.remove(lease.token);
        for (Lease lease : revoked) cleanup(lease, audit, "POLICY_REVOKED");
        return revoked.size();
    }

    public synchronized int activeCount() { return leases.size(); }

    public synchronized int activeCount(String capability) {
        int count = 0;
        for (Lease lease : leases.values()) if (lease.capability.equals(capability)) count++;
        return count;
    }

    public synchronized void close(CapabilityAuditSink audit) {
        List<Lease> current = new ArrayList<>(leases.values());
        leases.clear();
        for (Lease lease : current) cleanup(lease, audit, "SESSION_CLOSED");
    }

    @Override public void close() { close(CapabilityAuditSink.NO_OP); }

    private static void cleanup(Lease lease, CapabilityAuditSink audit, String reason) {
        try {
            lease.cleanup.cleanup();
            audit.record(new CapabilityAuditEvent(lease.id, lease.capability, "resource",
                    "cleanup", "RELEASED", reason));
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            audit.record(new CapabilityAuditEvent(lease.id, lease.capability, "resource",
                    "cleanup", "CLEANUP_FAILED", reason + ":" + error.getClass().getSimpleName()));
        }
    }

    private record Lease(long id, String capability, Object token, CleanupAction cleanup) { }
}
