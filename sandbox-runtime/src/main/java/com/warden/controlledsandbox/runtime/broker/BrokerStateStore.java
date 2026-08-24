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
    static final int MAX_VALIDATED_ARTIFACTS = 128;
    static final int MAX_ROUTE_PAYLOADS = 1024;
    static final int MAX_PREPARED_BYTES = 1024 * 1024;
    static final int MAX_VALIDATED_ARTIFACT_BYTES = 1024 * 1024;
    static final int MAX_ROUTE_BYTES = 512 * 1024;
    private static final int MAX_STATE_KEY_CHARS = 256;

    private final ConcurrentMap<String, Bundle> preparedSpecs = new ConcurrentHashMap<>();
    /** Immutable validation results retained across process/session stops. */
    private final ConcurrentMap<String, Bundle> validatedArtifacts = new ConcurrentHashMap<>();
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

    /**
     * Stores a broker-produced package validation artifact.  This cache is deliberately separate
     * from live prepared specs: stopping a Guest process must release the process lease while
     * retaining the immutable package/process validation boundary for the next ProcessRecord.
     * Cache pressure is non-fatal; a missed cache only takes the full validation path again.
     */
    synchronized void putValidatedArtifact(String key, Bundle artifact) {
        requireKey(key);
        Bundle bounded = boundedCopy(artifact, MAX_VALIDATED_ARTIFACT_BYTES,
                "VALIDATED_ARTIFACT");
        if (!validatedArtifacts.containsKey(key)
                && validatedArtifacts.size() >= MAX_VALIDATED_ARTIFACTS) {
            java.util.Iterator<String> iterator = validatedArtifacts.keySet().iterator();
            if (iterator.hasNext()) validatedArtifacts.remove(iterator.next());
        }
        validatedArtifacts.put(key, bounded);
    }

    synchronized Bundle validatedArtifact(String key) {
        Bundle value = validatedArtifacts.get(key);
        return value == null ? null : new Bundle(value);
    }

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

    public synchronized Bundle route(String token) {
        Bundle value = routePayloads.get(token);
        return value == null ? null : new Bundle(value);
    }

    public synchronized void rebindRoute(String token, long generation, String activityToken) {
        rebindRoute(token, generation, activityToken, null);
    }

    /** Rebinds a pending route and atomically replaces its recovery-only saved-state envelope. */
    public synchronized void rebindRoute(String token, long generation, String activityToken,
                                          Bundle recoveryState) {
        Bundle value = routePayloads.get(token);
        if (value == null) return;
        Bundle updated = new Bundle(value);
        updated.putLong(RuntimeKeys.GENERATION, generation);
        if (activityToken != null && !activityToken.trim().isEmpty()) {
            updated.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
        }
        if (recoveryState != null) updated.putAll(recoveryState);
        // Re-run the same bounded transport check used at initial route publication. A recovery
        // payload must never turn a previously safe route into a TransactionTooLarge route.
        Bundle bounded = boundedCopy(updated, MAX_ROUTE_BYTES, "ROUTE_PAYLOAD");
        routePayloads.put(token, bounded);
    }

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
