package com.warden.controlledsandbox;

import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.VirtualPageBlob;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Session-scoped bounded grants for binary values intentionally kept outside Binder pages. */
final class VirtualPageBlobStore implements AutoCloseable {
    static final int MAX_GRANTS = 64;
    private static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    private static final long TTL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(2);

    private final File root;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new LinkedHashMap<>();
    private int totalBytes;
    private boolean closed;

    VirtualPageBlobStore(File filesDir) {
        File parent = new File(java.util.Objects.requireNonNull(filesDir, "filesDir"), "virtual-page-blobs");
        root = new File(parent, java.util.UUID.randomUUID().toString());
    }

    synchronized VirtualPageBlob register(String scopeKey, int itemIndex, String fieldName, byte[] payload) {
        requireOpen();
        byte[] value = payload == null ? new byte[0] : payload.clone();
        pruneExpiredLocked(System.nanoTime());
        if (value.length > VirtualSystemServiceLimits.MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("ITEM_EXCEEDS_BINDER_BUDGET");
        }
        if (grants.size() >= MAX_GRANTS || totalBytes + value.length > MAX_TOTAL_BYTES) {
            throw new IllegalStateException("PAGE_BLOB_SESSION_BUDGET_EXCEEDED");
        }
        byte[] tokenBytes = new byte[32];
        String token;
        do {
            random.nextBytes(tokenBytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        } while (grants.containsKey(token));
        String digest = sha256(value);
        grants.put(token, new Grant(scopeKey, System.nanoTime() + TTL_NANOS, value, digest));
        totalBytes += value.length;
        return new VirtualPageBlob(itemIndex, fieldName, token, value.length, digest);
    }

    synchronized ParcelFileDescriptor open(String scopeKey, String token) {
        String normalized = normalizeToken(token);
        Grant grant = grant(scopeKey, normalized);
        byte[] payload = grant.payload.clone();
        if (!root.exists() && !root.mkdirs() && !root.isDirectory()) {
            throw new IllegalStateException("PAGE_BLOB_DIRECTORY_UNAVAILABLE");
        }
        File file = null;
        try {
            file = File.createTempFile("page-blob-", ".bin", root);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(payload);
                output.getFD().sync();
            }
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            grants.remove(normalized);
            totalBytes -= grant.payload.length;
            if (!file.delete()) file.deleteOnExit();
            return descriptor;
        } catch (IOException error) {
            if (file != null && file.exists() && !file.delete()) file.deleteOnExit();
            throw new IllegalStateException("PAGE_BLOB_OPEN_FAILED", error);
        }
    }

    synchronized byte[] payloadForTest(String scopeKey, String token) {
        return grant(scopeKey, normalizeToken(token)).payload.clone();
    }

    private Grant grant(String scopeKey, String token) {
        requireOpen();
        long now = System.nanoTime();
        Grant grant = grants.get(token);
        if (grant == null) throw new IllegalArgumentException("PAGE_BLOB_TOKEN_INVALID");
        if (grant.expiresAtNanos < now) {
            grants.remove(token);
            totalBytes -= grant.payload.length;
            throw new IllegalArgumentException("PAGE_BLOB_TOKEN_EXPIRED");
        }
        if (!grant.scopeKey.equals(scopeKey)) throw new SecurityException("PAGE_BLOB_SCOPE_MISMATCH");
        pruneExpiredLocked(now);
        return grant;
    }

    private static String normalizeToken(String token) {
        String value = token == null ? "" : token.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("PAGE_BLOB_TOKEN_INVALID");
        return value;
    }

    private void pruneExpiredLocked(long now) {
        Iterator<Map.Entry<String, Grant>> iterator = grants.entrySet().iterator();
        while (iterator.hasNext()) {
            Grant grant = iterator.next().getValue();
            if (grant.expiresAtNanos >= now) continue;
            iterator.remove();
            totalBytes -= grant.payload.length;
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        grants.clear();
        totalBytes = 0;
        deleteRecursively(root);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("PAGE_BLOB_STORE_CLOSED");
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void deleteRecursively(File value) {
        if (value == null || !value.exists()) return;
        File[] children = value.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!value.delete()) value.deleteOnExit();
    }

    private record Grant(String scopeKey, long expiresAtNanos, byte[] payload, String sha256) { }
}
