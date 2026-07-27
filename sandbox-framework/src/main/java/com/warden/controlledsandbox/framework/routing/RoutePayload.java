package com.warden.controlledsandbox.framework.routing;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Defensive, transport-neutral payload returned after a successful one-time consume. */
public final class RoutePayload {
    private final RouteKind kind;
    private final RouteOwner owner;
    private final long createdAtMillis;
    private final long expiresAtMillis;
    private final Map<String, String> metadata;
    private final byte[] bytes;

    RoutePayload(
            RouteKind kind,
            RouteOwner owner,
            long createdAtMillis,
            long expiresAtMillis,
            Map<String, String> metadata,
            byte[] bytes) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.metadata = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(metadata, "metadata")));
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public RouteKind kind() {
        return kind;
    }

    public RouteOwner owner() {
        return owner;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoutePayload that)) {
            return false;
        }
        return createdAtMillis == that.createdAtMillis
                && expiresAtMillis == that.expiresAtMillis
                && kind == that.kind
                && owner.equals(that.owner)
                && metadata.equals(that.metadata)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(kind, owner, createdAtMillis, expiresAtMillis, metadata);
        return 31 * result + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "RoutePayload[kind=" + kind
                + ", owner=" + owner
                + ", createdAtMillis=" + createdAtMillis
                + ", expiresAtMillis=" + expiresAtMillis
                + ", metadata=" + metadata
                + ", byteCount=" + bytes.length
                + "]";
    }
}
