package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.content.ContentProvider;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Guest-process owner for Provider file resources referenced by Broker-issued tokens. */
public final class GuestProviderFileTransport {
    public static final long DEFAULT_LEASE_TTL_MS = 120_000L;
    static final long MAX_LEASE_TTL_MS = 600_000L;
    static final int MAX_ACTIVE_LEASES = 64;

    private static final String KIND_FILE = "FILE";
    private static final String KIND_ASSET = "ASSET";
    private static final String KIND_TYPED_ASSET = "TYPED_ASSET";

    private static final class Lease {
        public final String token;
        final String ownerSessionId;
        final long generation;
        final String kind;
        final Closeable resource;
        public final long expiresAtMs;

        Lease(String token, String ownerSessionId, long generation, String kind,
              Closeable resource, long expiresAtMs) {
            this.token = token;
            this.ownerSessionId = ownerSessionId;
            this.generation = generation;
            this.kind = kind;
            this.resource = resource;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final Map<String, Lease> leases = new LinkedHashMap<>();

    public synchronized Bundle openFile(ContentProvider provider, Uri uri, String mode, String token,
                                 String ownerSessionId, long generation, long nowMs, long ttlMs) throws Exception {
        String normalized = ProviderFileModes.requireAllowed(mode);
        ParcelFileDescriptor descriptor = provider.openFile(uri, normalized);
        if (descriptor == null) throw new IllegalStateException("PROVIDER_OPEN_FILE_RETURNED_NULL");
        return retain(token, ownerSessionId, generation, KIND_FILE, descriptor, null,
                nowMs, ttlMs, normalized, "");
    }

    public synchronized Bundle openAssetFile(ContentProvider provider, Uri uri, String mode, String token,
                                      String ownerSessionId, long generation, long nowMs, long ttlMs) throws Exception {
        String normalized = ProviderFileModes.requireAllowed(mode);
        AssetFileDescriptor descriptor = provider.openAssetFile(uri, normalized);
        if (descriptor == null) throw new IllegalStateException("PROVIDER_OPEN_ASSET_FILE_RETURNED_NULL");
        return retain(token, ownerSessionId, generation, KIND_ASSET, descriptor,
                descriptor, nowMs, ttlMs, normalized, "");
    }

    public synchronized Bundle openTypedAssetFile(ContentProvider provider, Uri uri, String mimeType, Bundle options,
                                           String token, String ownerSessionId, long generation,
                                           long nowMs, long ttlMs) throws Exception {
        requireText(mimeType, RuntimeKeys.PROVIDER_MIME_TYPE);
        AssetFileDescriptor descriptor = provider.openTypedAssetFile(uri, mimeType,
                options == null ? null : new Bundle(options));
        if (descriptor == null) throw new IllegalStateException("PROVIDER_OPEN_TYPED_ASSET_FILE_RETURNED_NULL");
        return retain(token, ownerSessionId, generation, KIND_TYPED_ASSET, descriptor,
                descriptor, nowMs, ttlMs, "r", mimeType);
    }

    public synchronized Bundle close(String token, String ownerSessionId, long generation) {
        purgeExpired(now());
        Lease lease = leases.get(token);
        if (lease == null) throw new SecurityException("UNKNOWN_PROVIDER_FILE_LEASE");
        validateOwner(lease, ownerSessionId, generation);
        leases.remove(token);
        closeQuietly(lease.resource);
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "PROVIDER_FILE_CLOSED");
        out.putString(RuntimeKeys.FILE_TOKEN, token);
        out.putString(RuntimeKeys.FILE_DESCRIPTOR_KIND, lease.kind);
        return out;
    }

    synchronized int closeSession(String ownerSessionId, long generation) {
        int closed = 0;
        for (Lease lease : new ArrayList<>(leases.values())) {
            if (lease.ownerSessionId.equals(ownerSessionId) && lease.generation == generation) {
                leases.remove(lease.token);
                closeQuietly(lease.resource);
                closed++;
            }
        }
        return closed;
    }

    public synchronized int closeAll() {
        int count = leases.size();
        for (Lease lease : leases.values()) closeQuietly(lease.resource);
        leases.clear();
        return count;
    }

    synchronized int purgeExpired(long nowMs) {
        int count = 0;
        for (Lease lease : new ArrayList<>(leases.values())) {
            if (lease.expiresAtMs <= nowMs) {
                leases.remove(lease.token);
                closeQuietly(lease.resource);
                count++;
            }
        }
        return count;
    }

    synchronized int size(long nowMs) {
        purgeExpired(nowMs);
        return leases.size();
    }

    private Bundle retain(String token, String ownerSessionId, long generation, String kind,
                          Closeable resource, AssetFileDescriptor asset, long nowMs, long ttlMs,
                          String mode, String mimeType) {
        try {
            requireText(token, RuntimeKeys.FILE_TOKEN);
            requireText(ownerSessionId, RuntimeKeys.SESSION_ID);
            if (generation < 1) throw new IllegalArgumentException("generation must be positive");
            if (ttlMs < 1 || ttlMs > MAX_LEASE_TTL_MS) {
                throw new IllegalArgumentException("INVALID_PROVIDER_FILE_TTL");
            }
            purgeExpired(nowMs);
            if (leases.size() >= MAX_ACTIVE_LEASES) {
                throw new IllegalStateException("GUEST_PROVIDER_FILE_CAPACITY_EXHAUSTED");
            }
            if (leases.containsKey(token)) throw new SecurityException("DUPLICATE_PROVIDER_FILE_TOKEN");
            long expiresAtMs = Math.addExact(nowMs, ttlMs);
            leases.put(token, new Lease(token, ownerSessionId, generation, kind, resource, expiresAtMs));

            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "PROVIDER_FILE_OPEN");
            out.putString(RuntimeKeys.FILE_TOKEN, token);
            out.putString(RuntimeKeys.FILE_DESCRIPTOR_KIND, kind);
            out.putString(RuntimeKeys.PROVIDER_FILE_MODE, mode);
            out.putString(RuntimeKeys.PROVIDER_MIME_TYPE, mimeType);
            out.putLong(RuntimeKeys.FILE_EXPIRES_AT, expiresAtMs);
            if (asset == null) {
                out.putParcelable(RuntimeKeys.FILE_DESCRIPTOR, (ParcelFileDescriptor) resource);
                out.putLong(RuntimeKeys.FILE_START_OFFSET, 0L);
                out.putLong(RuntimeKeys.FILE_DECLARED_LENGTH, -1L);
            } else {
                out.putParcelable(RuntimeKeys.ASSET_FILE_DESCRIPTOR, asset);
                out.putLong(RuntimeKeys.FILE_START_OFFSET, asset.getStartOffset());
                out.putLong(RuntimeKeys.FILE_DECLARED_LENGTH, asset.getDeclaredLength());
            }
            return out;
        } catch (RuntimeException error) {
            closeQuietly(resource);
            throw error;
        }
    }

    private static void validateOwner(Lease lease, String ownerSessionId, long generation) {
        if (!lease.ownerSessionId.equals(ownerSessionId)) throw new SecurityException("PROVIDER_FILE_OWNER_MISMATCH");
        if (lease.generation != generation) throw new SecurityException("PROVIDER_FILE_GENERATION_MISMATCH");
    }

    private static void closeQuietly(Closeable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (IOException ignored) { }
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
