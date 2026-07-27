package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Concurrent, defensive-copy storage for Binder-visible broker state. */
public final class BrokerStateStore {
    private final ConcurrentMap<String, Bundle> preparedSpecs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bundle> routePayloads = new ConcurrentHashMap<>();

    void putPrepared(String key, Bundle spec) {
        requireKey(key);
        preparedSpecs.put(key, copy(spec));
    }

    Bundle prepared(String key) {
        Bundle value = preparedSpecs.get(key);
        return value == null ? null : new Bundle(value);
    }

    void removePrepared(String key) { preparedSpecs.remove(key); }

    public void putRoute(String token, Bundle payload) {
        requireKey(token);
        Bundle previous = routePayloads.putIfAbsent(token, copy(payload));
        if (previous != null) throw new IllegalStateException("DUPLICATE_ROUTE_PAYLOAD");
    }

    public Bundle consumeRoute(String token) {
        Bundle value = routePayloads.remove(token);
        return value == null ? null : new Bundle(value);
    }

    public void removeRoute(String token) { routePayloads.remove(token); }

    int purgeRoutes(String sessionId, long generation) {
        int removed = 0;
        for (Map.Entry<String, Bundle> entry : routePayloads.entrySet()) {
            Bundle value = entry.getValue();
            if (sessionId.equals(value.getString(RuntimeKeys.SESSION_ID, ""))
                    && generation == value.getLong(RuntimeKeys.GENERATION, -1)
                    && routePayloads.remove(entry.getKey(), value)) removed++;
        }
        return removed;
    }

    int pendingRoutes() { return routePayloads.size(); }
    int preparedCount() { return preparedSpecs.size(); }

    private static Bundle copy(Bundle value) {
        if (value == null) throw new IllegalArgumentException("Bundle is required");
        return new Bundle(value);
    }

    private static void requireKey(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("state key is required");
    }

}
