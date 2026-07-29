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
    private record Capability(Binder token, IVirtualSystemServiceSession session) { }
    private final RuntimeVirtualSystemServicePackageClient client;
    private final IBinder runtimeBroker;
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();

    RuntimeSystemServiceCoordinator(RuntimeVirtualSystemServicePackageClient client, IBinder runtimeBroker) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.runtimeBroker = java.util.Objects.requireNonNull(runtimeBroker, "runtimeBroker");
    }

    synchronized void attach(GuestSession guest, Bundle spec) throws Exception {
        String key = key(guest.sessionId(), guest.generation());
        Capability current = capabilities.get(key);
        if (current == null) {
            Binder token = new Binder();
            IVirtualSystemServiceSession session = client.open(token, guest.packageName(),
                    guest.virtualUserId(), spec.getInt(RuntimeKeys.VIRTUAL_UID, -1),
                    guest.processName(), guest.generation(), guest.packageRevision());
            current = new Capability(token, session); capabilities.put(key, current);
        }
        if (!current.session().asBinder().isBinderAlive()) {
            capabilities.remove(key); throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DEAD");
        }
        spec.putBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER, current.session().asBinder());
        spec.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER, runtimeBroker);
    }

    synchronized void stop(GuestSession guest) { closeKey(key(guest.sessionId(), guest.generation())); }
    synchronized int size() { return capabilities.size(); }

    private void closeKey(String key) {
        Capability removed = capabilities.remove(key);
        if (removed == null) return;
        try { removed.session().close(); } catch (Exception ignored) { }
    }
    @Override public synchronized void close() {
        for (String key : capabilities.keySet().toArray(new String[0])) closeKey(key);
        client.close();
    }
    private static String key(String sessionId, long generation) { return sessionId + "#g" + generation; }
}
