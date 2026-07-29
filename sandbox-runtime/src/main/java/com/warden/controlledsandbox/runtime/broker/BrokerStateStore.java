package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import android.os.Parcel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Concurrent, defensive-copy storage for bounded Binder-visible broker state. */
public final class BrokerStateStore {
    static final int MAX_PREPARED_SPECS = 64;
    static final int MAX_ROUTE_PAYLOADS = 1024;
    static final int MAX_PREPARED_BYTES = 1024 * 1024;
    static final int MAX_ROUTE_BYTES = 512 * 1024;
    private static final int MAX_STATE_KEY_CHARS = 256;

    private final ConcurrentMap<String, Bundle> preparedSpecs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bundle> routePayloads = new ConcurrentHashMap<>();

    synchronized void putPrepared(String key, Bundle spec) {
        requireKey(key);
        Bundle bounded = boundedCopy(spec, MAX_PREPARED_BYTES, "PREPARED_SPEC");
        if (!preparedSpecs.containsKey(key) && preparedSpecs.size() >= MAX_PREPARED_SPECS) {
            throw new IllegalStateException("PREPARED_SPEC_LIMIT_EXCEEDED");
        }
        preparedSpecs.put(key, bounded);
    }

    synchronized Bundle prepared(String key) {
        Bundle value = preparedSpecs.get(key);
        return value == null ? null : new Bundle(value);
    }

    synchronized void removePrepared(String key) { preparedSpecs.remove(key); }

    public synchronized void putRoute(String token, Bundle payload) {
        requireKey(token);
        if (routePayloads.containsKey(token)) throw new IllegalStateException("DUPLICATE_ROUTE_PAYLOAD");
        if (routePayloads.size() >= MAX_ROUTE_PAYLOADS) {
            throw new IllegalStateException("ROUTE_PAYLOAD_LIMIT_EXCEEDED");
        }
        routePayloads.put(token, boundedCopy(payload, MAX_ROUTE_BYTES, "ROUTE_PAYLOAD"));
    }

    public synchronized Bundle consumeRoute(String token) {
        Bundle value = routePayloads.remove(token);
        return value == null ? null : new Bundle(value);
    }

    public synchronized void removeRoute(String token) { routePayloads.remove(token); }

    synchronized int purgeRoutes(String sessionId, long generation) {
        int removed = 0;
        for (Map.Entry<String, Bundle> entry : routePayloads.entrySet()) {
            Bundle value = entry.getValue();
            if (sessionId.equals(value.getString(RuntimeKeys.SESSION_ID, ""))
                    && generation == value.getLong(RuntimeKeys.GENERATION, -1)
                    && routePayloads.remove(entry.getKey(), value)) removed++;
        }
        return removed;
    }

    synchronized int pendingRoutes() { return routePayloads.size(); }
    synchronized int preparedCount() { return preparedSpecs.size(); }

    private static Bundle boundedCopy(Bundle value, int maxBytes, String label) {
        if (value == null) throw new IllegalArgumentException("Bundle is required");
        Bundle copy = new Bundle(value);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(copy);
            int bytes = parcel.dataSize();
            if (bytes < 0 || bytes > maxBytes) {
                throw new IllegalArgumentException(label + "_TOO_LARGE:" + bytes);
            }
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException
                    && error.getMessage() != null && error.getMessage().startsWith(label + "_TOO_LARGE")) {
                throw error;
            }
            throw new IllegalArgumentException(label + "_UNMARSHALLABLE", error);
        } finally {
            parcel.recycle();
        }
        return copy;
    }

    private static void requireKey(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("state key is required");
        if (value.length() > MAX_STATE_KEY_CHARS) throw new IllegalArgumentException("state key is too long");
    }
}
