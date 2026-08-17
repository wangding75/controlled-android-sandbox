package com.warden.controlledsandbox.runtime.broker;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns scoped Binder capabilities passed from Runtime Broker to Guest generations. */
final class RuntimeSystemServiceCoordinator implements AutoCloseable {
    @FunctionalInterface
    interface SessionFactory {
        IVirtualSystemServiceSession open(IBinder clientToken, String packageName,
                                          int virtualUserId, int virtualUid,
                                          String processName, long generation,
                                          String packageRevision) throws Exception;
    }

    private record Capability(Binder token, IVirtualSystemServiceSession session) { }
    private final SessionFactory sessionFactory;
    private final AutoCloseable clientOwner;
    private final IBinder runtimeBroker;
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();

    RuntimeSystemServiceCoordinator(RuntimeVirtualSystemServicePackageClient client, IBinder runtimeBroker) {
        this(java.util.Objects.requireNonNull(client, "client")::open, client, runtimeBroker);
    }

    RuntimeSystemServiceCoordinator(SessionFactory sessionFactory, AutoCloseable clientOwner,
                                    IBinder runtimeBroker) {
        this.sessionFactory = java.util.Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.clientOwner = java.util.Objects.requireNonNull(clientOwner, "clientOwner");
        this.runtimeBroker = java.util.Objects.requireNonNull(runtimeBroker, "runtimeBroker");
    }

    synchronized void attach(GuestSession guest, Bundle spec) throws Exception {
        String key = key(guest.sessionId(), guest.generation());
        Capability current = capabilities.get(key);
        if (current != null && !isAlive(current)) {
            capabilities.remove(key);
            closeCapability(current);
            current = null;
        }
        if (current == null) current = openLiveCapability(key, guest, spec);
        spec.putBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER, current.session().asBinder());
        spec.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER, runtimeBroker);
    }

    synchronized void stop(GuestSession guest) { closeKey(key(guest.sessionId(), guest.generation())); }

    /** Returns the live, generation-scoped virtual system-service capability for Broker checks. */
    synchronized IVirtualSystemServiceSession sessionFor(GuestSession guest) {
        if (guest == null) throw new IllegalArgumentException("guest is required");
        Capability capability = capabilities.get(key(guest.sessionId(), guest.generation()));
        if (capability == null || !isAlive(capability)) {
            throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_NOT_LIVE");
        }
        return capability.session();
    }
    synchronized int size() { return capabilities.size(); }

    private void closeKey(String key) {
        Capability removed = capabilities.remove(key);
        closeCapability(removed);
    }
    @Override public synchronized void close() {
        for (String key : capabilities.keySet().toArray(new String[0])) closeKey(key);
        try { clientOwner.close(); } catch (Exception ignored) { }
    }

    private Capability openLiveCapability(String key, GuestSession guest, Bundle spec) throws Exception {
        Binder token = new Binder();
        IVirtualSystemServiceSession session = sessionFactory.open(token, guest.packageName(),
                guest.virtualUserId(), spec.getInt(RuntimeKeys.VIRTUAL_UID, -1),
                guest.processName(), guest.generation(), guest.packageRevision());
        if (session == null) throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_MISSING");
        Capability opened = new Capability(token, session);
        if (!isAlive(opened)) {
            closeCapability(opened);
            throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DEAD_AFTER_OPEN");
        }
        capabilities.put(key, opened);
        return opened;
    }

    private static boolean isAlive(Capability capability) {
        return capability.session().asBinder().isBinderAlive();
    }

    private static void closeCapability(Capability capability) {
        if (capability == null) return;
        try { capability.session().close(); } catch (Exception ignored) { }
    }

    private static String key(String sessionId, long generation) { return sessionId + "#g" + generation; }
}
