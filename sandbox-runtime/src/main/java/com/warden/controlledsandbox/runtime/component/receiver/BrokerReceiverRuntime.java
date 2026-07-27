package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.receiver.DynamicReceiverRegistry;
import com.warden.controlledsandbox.domain.session.GuestSession;
import java.util.ArrayList;
import java.util.List;

/** Broker-owned authority for dynamic receiver registration and resolution. */
public final class BrokerReceiverRuntime {
    public static final class Reservation {
        private final String id;
        private final String sessionId;
        private final long generation;

        private Reservation(String id, String sessionId, long generation) {
            this.id = id;
            this.sessionId = sessionId;
            this.generation = generation;
        }
    }

    private final DynamicReceiverRegistry registry = new DynamicReceiverRegistry();

    public Reservation reserveRegistration(Bundle request, GuestSession session) {
        String id = required(request, RuntimeKeys.RECEIVER_ID);
        ArrayList<String> actions = request.getStringArrayList(RuntimeKeys.RECEIVER_ACTIONS);
        registry.register(id, session.packageName(), session.sessionId(), session.generation(),
                session.virtualUserId(), required(request, RuntimeKeys.COMPONENT_CLASS), actions,
                request.getBoolean(RuntimeKeys.RECEIVER_EXPORTED, false));
        return new Reservation(id, session.sessionId(), session.generation());
    }

    public void rollbackRegistration(Reservation reservation) {
        if (reservation == null) return;
        try {
            registry.unregister(reservation.id, reservation.sessionId, reservation.generation);
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Rollback is idempotent. Missing ownership means another terminal path already removed it.
        }
    }

    public void requireOwnedRegistration(Bundle request, GuestSession session) {
        registry.requireOwned(required(request, RuntimeKeys.RECEIVER_ID),
                session.sessionId(), session.generation());
    }

    public DynamicReceiverRegistry.Registration commitUnregister(Bundle request, GuestSession session) {
        return registry.unregister(required(request, RuntimeKeys.RECEIVER_ID),
                session.sessionId(), session.generation());
    }

    public List<DynamicReceiverRegistry.Registration> resolve(
            String action, int virtualUserId, String senderSessionId, boolean externalBroadcast) {
        return registry.resolve(action, virtualUserId, senderSessionId, externalBroadcast);
    }

    public int removeSession(GuestSession session) {
        return registry.removeSession(session.sessionId(), session.generation());
    }

    public int removeInstance(String packageName, int virtualUserId) {
        return registry.removeInstance(packageName, virtualUserId);
    }

    public int clear() { return registry.clear(); }

    public DynamicReceiverRegistry.Snapshot snapshot() { return registry.snapshot(); }

    public int size() { return registry.size(); }

    private static String required(Bundle value, String key) {
        String result = value.getString(key, "");
        if (result == null || result.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return result;
    }
}
