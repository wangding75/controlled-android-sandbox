package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.receiver.BroadcastIntent;
import com.warden.controlledsandbox.domain.component.receiver.DynamicReceiverRegistry;
import com.warden.controlledsandbox.domain.component.receiver.ManifestReceiverRegistry;
import com.warden.controlledsandbox.domain.session.GuestSession;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
        registry.register(id, session.packageName(), session.sessionId(), session.generation(),
                session.virtualUserId(), required(request, RuntimeKeys.COMPONENT_CLASS),
                filter(request), request.getString(RuntimeKeys.RECEIVER_PERMISSION, ""),
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

    public List<DynamicReceiverRegistry.Registration> resolve(
            Bundle request, int virtualUserId, String senderSessionId, boolean externalBroadcast) {
        return registry.resolve(intent(request), virtualUserId, senderSessionId, externalBroadcast);
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

    private static ManifestReceiverRegistry.Filter filter(Bundle request) {
        ArrayList<String> actions = request.getStringArrayList(RuntimeKeys.RECEIVER_ACTIONS);
        ArrayList<String> categories = request.getStringArrayList(RuntimeKeys.RECEIVER_CATEGORIES);
        if (actions != null && actions.size() > DynamicReceiverRegistry.MAX_ACTIONS_PER_REGISTRATION) {
            throw new IllegalArgumentException("DYNAMIC_RECEIVER_ACTION_LIMIT_EXCEEDED");
        }
        int count = request.getInt(RuntimeKeys.RECEIVER_DATA_RULE_COUNT, 0);
        if (count < 0 || count > ManifestReceiverRegistry.MAX_DATA_RULES_PER_FILTER) {
            throw new IllegalArgumentException("DYNAMIC_RECEIVER_DATA_RULE_LIMIT_EXCEEDED");
        }
        ArrayList<ManifestReceiverRegistry.DataRule> rules = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Bundle rule = request.getBundle(RuntimeKeys.RECEIVER_DATA_RULE_PREFIX + index);
            if (rule == null) throw new IllegalArgumentException("DYNAMIC_RECEIVER_DATA_RULE_MISSING");
            rules.add(new ManifestReceiverRegistry.DataRule(
                    rule.getString(RuntimeKeys.BROADCAST_SCHEME, ""),
                    rule.getString(RuntimeKeys.BROADCAST_HOST, ""),
                    rule.getInt(RuntimeKeys.BROADCAST_PORT, -1),
                    rule.getString(RuntimeKeys.BROADCAST_PATH, ""),
                    rule.getString(RuntimeKeys.RECEIVER_DATA_PATH_PREFIX, ""),
                    rule.getString(RuntimeKeys.RECEIVER_DATA_PATH_PATTERN, ""),
                    rule.getString(RuntimeKeys.BROADCAST_MIME_TYPE, "")));
        }
        return new ManifestReceiverRegistry.Filter(
                request.getInt(RuntimeKeys.RECEIVER_PRIORITY, 0),
                new LinkedHashSet<>(actions == null ? List.of() : actions),
                new LinkedHashSet<>(categories == null ? List.of() : categories), rules);
    }

    private static BroadcastIntent intent(Bundle request) {
        ArrayList<String> categories = request.getStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES);
        return new BroadcastIntent(required(request, com.warden.controlledsandbox.runtime.protocol.ComponentOperations.ACTION),
                new LinkedHashSet<>(categories == null ? List.of() : categories),
                request.getString(RuntimeKeys.BROADCAST_SCHEME, ""),
                request.getString(RuntimeKeys.BROADCAST_HOST, ""),
                request.getInt(RuntimeKeys.BROADCAST_PORT, -1),
                request.getString(RuntimeKeys.BROADCAST_PATH, ""),
                request.getString(RuntimeKeys.BROADCAST_MIME_TYPE, ""));
    }

    private static String required(Bundle value, String key) {
        String result = value.getString(key, "");
        if (result == null || result.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return result;
    }
}
