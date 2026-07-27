package com.warden.controlledsandbox.framework.routing;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Broker-owned bounded storage for payloads referenced by opaque one-time route tokens.
 *
 * <p>Only the token crosses the Host Stub Activity boundary. The payload remains in the broker,
 * is bound to an exact virtual-process generation, expires quickly, and is removed atomically on
 * successful consume.</p>
 */
public final class OneTimeRouteStore {
    public static final int DEFAULT_MAX_ENTRIES = 4096;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;
    public static final Duration DEFAULT_MAX_TTL = Duration.ofMinutes(10);

    private static final int MAX_METADATA_ENTRIES = 64;
    private static final int MAX_METADATA_KEY_LENGTH = 128;
    private static final int MAX_METADATA_VALUE_LENGTH = 4096;
    private static final int MAX_METADATA_CHARACTERS = 32_768;

    private final Clock clock;
    private final int maxEntries;
    private final int maxPayloadBytes;
    private final Duration maxTtl;
    private final LinkedHashMap<String, StoredRoute> routes = new LinkedHashMap<>();

    public OneTimeRouteStore() {
        this(Clock.systemUTC(), DEFAULT_MAX_ENTRIES, DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_MAX_TTL);
    }

    public OneTimeRouteStore(
            Clock clock,
            int maxEntries,
            int maxPayloadBytes,
            Duration maxTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        Objects.requireNonNull(maxTtl, "maxTtl");
        if (maxTtl.isZero() || maxTtl.isNegative()) {
            throw new IllegalArgumentException("maxTtl must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxTtl = maxTtl;
    }

    public synchronized RouteToken put(
            RouteOwner owner,
            RouteKind kind,
            byte[] bytes,
            Map<String, String> metadata,
            Duration ttl) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        byte[] payloadCopy = Objects.requireNonNull(bytes, "bytes").clone();
        if (payloadCopy.length > maxPayloadBytes) {
            throw new IllegalArgumentException("route payload exceeds byte limit");
        }
        Map<String, String> metadataCopy = validateMetadata(metadata);
        Duration normalizedTtl = validateTtl(ttl);
        long now = clock.millis();
        purgeExpiredAt(now);
        if (routes.size() >= maxEntries) {
            throw new IllegalStateException("route store capacity exhausted");
        }
        long expiresAt = Math.addExact(now, normalizedTtl.toMillis());
        String token = nextUniqueToken();
        routes.put(token, new StoredRoute(
                kind,
                owner,
                now,
                expiresAt,
                metadataCopy,
                payloadCopy));
        return new RouteToken(token, expiresAt);
    }

    /**
     * Consumes exactly once. Identity or kind mismatch is rejected without deleting the token.
     */
    public synchronized Optional<RoutePayload> consume(
            String token,
            RouteOwner expectedOwner,
            RouteKind expectedKind) {
        String normalizedToken = requireText(token, "token");
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        Objects.requireNonNull(expectedKind, "expectedKind");
        long now = clock.millis();
        StoredRoute route = routes.get(normalizedToken);
        if (route == null) {
            return Optional.empty();
        }
        if (route.expiresAtMillis <= now) {
            routes.remove(normalizedToken);
            return Optional.empty();
        }
        if (!route.owner.equals(expectedOwner)) {
            throw new SecurityException("route token owner mismatch");
        }
        if (route.kind != expectedKind) {
            throw new SecurityException("route token kind mismatch");
        }
        routes.remove(normalizedToken);
        return Optional.of(route.toPayload());
    }

    public synchronized boolean revoke(String token) {
        return routes.remove(requireText(token, "token")) != null;
    }

    /** Removes all tokens issued to this exact virtual-process generation. */
    public synchronized int revokeOwner(RouteOwner owner) {
        Objects.requireNonNull(owner, "owner");
        int removed = 0;
        Iterator<Map.Entry<String, StoredRoute>> iterator = routes.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().owner.equals(owner)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Removes tokens for stale generations up to and including {@code staleGeneration}. */
    public synchronized int revokeStaleGenerations(
            int virtualUserId,
            String packageName,
            String processName,
            long staleGeneration) {
        if (virtualUserId < 0) {
            throw new IllegalArgumentException("virtualUserId must be non-negative");
        }
        String normalizedPackage = requireText(packageName, "packageName");
        String normalizedProcess = requireText(processName, "processName");
        if (staleGeneration < 1) {
            throw new IllegalArgumentException("staleGeneration must be positive");
        }
        int removed = 0;
        Iterator<Map.Entry<String, StoredRoute>> iterator = routes.entrySet().iterator();
        while (iterator.hasNext()) {
            RouteOwner owner = iterator.next().getValue().owner;
            if (owner.virtualUserId() == virtualUserId
                    && owner.packageName().equals(normalizedPackage)
                    && owner.processName().equals(normalizedProcess)
                    && owner.processGeneration() <= staleGeneration) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized int purgeExpired() {
        return purgeExpiredAt(clock.millis());
    }

    public synchronized int size() {
        purgeExpiredAt(clock.millis());
        return routes.size();
    }

    private int purgeExpiredAt(long now) {
        int removed = 0;
        Iterator<Map.Entry<String, StoredRoute>> iterator = routes.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAtMillis <= now) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private Duration validateTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (ttl.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException("ttl exceeds maximum");
        }
        if (ttl.toMillis() < 1) {
            throw new IllegalArgumentException("ttl must be at least one millisecond");
        }
        return ttl;
    }

    private static Map<String, String> validateMetadata(Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("route metadata exceeds entry limit");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        int characters = 0;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = requireText(entry.getKey(), "metadata key");
            String value = Objects.requireNonNull(entry.getValue(), "metadata value");
            if (key.length() > MAX_METADATA_KEY_LENGTH) {
                throw new IllegalArgumentException("metadata key is too long");
            }
            if (value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new IllegalArgumentException("metadata value is too long");
            }
            characters = Math.addExact(characters, key.length() + value.length());
            if (characters > MAX_METADATA_CHARACTERS) {
                throw new IllegalArgumentException("route metadata exceeds aggregate size limit");
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }

    private String nextUniqueToken() {
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = UUID.randomUUID().toString();
            if (!routes.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("unable to allocate unique route token");
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static final class StoredRoute {
        private final RouteKind kind;
        private final RouteOwner owner;
        private final long createdAtMillis;
        private final long expiresAtMillis;
        private final Map<String, String> metadata;
        private final byte[] bytes;

        private StoredRoute(
                RouteKind kind,
                RouteOwner owner,
                long createdAtMillis,
                long expiresAtMillis,
                Map<String, String> metadata,
                byte[] bytes) {
            this.kind = kind;
            this.owner = owner;
            this.createdAtMillis = createdAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.metadata = metadata;
            this.bytes = bytes;
        }

        private RoutePayload toPayload() {
            return new RoutePayload(kind, owner, createdAtMillis, expiresAtMillis, metadata, bytes);
        }
    }
}
