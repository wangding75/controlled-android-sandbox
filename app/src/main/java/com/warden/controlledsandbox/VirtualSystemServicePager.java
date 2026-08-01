package com.warden.controlledsandbox;

import android.os.Parcel;
import android.os.Parcelable;
import com.warden.controlledsandbox.contract.VirtualPageBlob;
import com.warden.controlledsandbox.contract.VirtualPageRequest;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared page-token, revision, byte-budget, and binary-offload implementation. */
final class VirtualSystemServicePager implements AutoCloseable {
    static final int LARGE_BINARY_THRESHOLD = 64 * 1024;
    static final int LEGACY_MAX_ITEMS = 32;
    static final int LEGACY_MAX_BYTES = 128 * 1024;
    private static final int PAGE_OVERHEAD_BYTES = 8 * 1024;
    private static final long TOKEN_TTL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
    private static final byte TOKEN_VERSION = 1;

    interface BinaryAdapter<T extends Parcelable> {
        String fieldName();
        byte[] payload(T value);
        T withoutPayload(T value);
    }

    record PageSlice<T extends Parcelable>(List<T> items, List<VirtualPageBlob> blobs,
                                            String nextPageToken, long snapshotRevision,
                                            int estimatedBytes) { }

    private final byte[] tokenSecret = new byte[32];
    private final VirtualPageBlobStore blobs;

    VirtualSystemServicePager(java.io.File filesDir) {
        new SecureRandom().nextBytes(tokenSecret);
        blobs = new VirtualPageBlobStore(filesDir);
    }

    <T extends Parcelable> PageSlice<T> page(String collection, String scopeKey,
            List<T> source, VirtualPageRequest request, BinaryAdapter<T> binaryAdapter) {
        java.util.Objects.requireNonNull(request, "request");
        List<T> values = List.copyOf(source == null ? List.of() : source);
        long revision = revision(values);
        int start = request.pageToken().isEmpty() ? 0
                : decodeToken(request.pageToken(), collection, scopeKey, revision, values.size());
        ArrayList<T> items = new ArrayList<>();
        ArrayList<VirtualPageBlob> pageBlobs = new ArrayList<>();
        int estimated = PAGE_OVERHEAD_BYTES;
        int index = start;
        while (index < values.size() && items.size() < request.maxItems()) {
            T original = values.get(index);
            byte[] binary = binaryAdapter == null ? new byte[0] : binaryAdapter.payload(original);
            boolean offload = binaryAdapter != null && binary.length > LARGE_BINARY_THRESHOLD;
            if (offload && pageBlobs.size() >= VirtualPageBlobStore.MAX_GRANTS) break;
            T transmitted = offload ? binaryAdapter.withoutPayload(original) : original;
            int itemBytes = measuredBytes(transmitted);
            int blobBytes = 0;
            if (offload) {
                VirtualPageBlob placeholder = new VirtualPageBlob(items.size(), binaryAdapter.fieldName(),
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", binary.length,
                        "0000000000000000000000000000000000000000000000000000000000000000");
                blobBytes = measuredBytes(placeholder);
            }
            if (itemBytes + blobBytes + PAGE_OVERHEAD_BYTES > request.maxBytes()) {
                if (items.isEmpty()) throw new IllegalStateException("ITEM_EXCEEDS_BINDER_BUDGET");
                break;
            }
            if (!items.isEmpty() && estimated + itemBytes + blobBytes > request.maxBytes()) break;
            VirtualPageBlob descriptor = null;
            if (offload) {
                try {
                    descriptor = blobs.register(scopeKey, items.size(), binaryAdapter.fieldName(), binary);
                } catch (IllegalStateException error) {
                    if (!"PAGE_BLOB_SESSION_BUDGET_EXCEEDED".equals(error.getMessage()) || items.isEmpty()) {
                        throw error;
                    }
                    break;
                }
            }
            items.add(transmitted);
            estimated += itemBytes;
            if (descriptor != null) {
                pageBlobs.add(descriptor);
                estimated += measuredBytes(descriptor);
            }
            index++;
        }
        String next = index < values.size() ? encodeToken(collection, scopeKey, revision, index) : "";
        estimated += stringBytes(next) + 16;
        if (estimated > request.maxBytes()) {
            throw new IllegalStateException("PAGE_BUDGET_ESTIMATE_MISMATCH");
        }
        return new PageSlice<>(List.copyOf(items), List.copyOf(pageBlobs), next, revision, estimated);
    }

    <T extends Parcelable> List<T> legacy(PageSlice<T> page) {
        if (!page.nextPageToken().isEmpty() || !page.blobs().isEmpty()) {
            throw new IllegalStateException("PAGING_REQUIRED");
        }
        return page.items();
    }

    android.os.ParcelFileDescriptor openBlob(String scopeKey, String token) {
        return blobs.open(scopeKey, token);
    }

    byte[] blobForTest(String scopeKey, String token) { return blobs.payloadForTest(scopeKey, token); }

    @Override public void close() { blobs.close(); }

    static int measuredBytes(Parcelable value) {
        Parcel parcel = Parcel.obtain();
        try {
            value.writeToParcel(parcel, 0);
            return Math.max(16, parcel.dataSize() + 16);
        } finally {
            parcel.recycle();
        }
    }

    private String encodeToken(String collection, String scopeKey, long revision, int offset) {
        long expiry = System.nanoTime() + TOKEN_TTL_NANOS;
        ByteBuffer payload = ByteBuffer.allocate(1 + 16 + 16 + 8 + 4 + 8);
        payload.put(TOKEN_VERSION);
        payload.put(hash16(collection));
        payload.put(hash16(scopeKey));
        payload.putLong(revision);
        payload.putInt(offset);
        payload.putLong(expiry);
        byte[] body = payload.array();
        byte[] mac = hmac(body);
        ByteBuffer token = ByteBuffer.allocate(body.length + mac.length);
        token.put(body); token.put(mac);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token.array());
    }

    private int decodeToken(String value, String collection, String scopeKey,
            long revision, int collectionSize) {
        final byte[] token;
        try {
            token = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("PAGE_TOKEN_INVALID", error);
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(token).equals(value)) {
            throw new IllegalArgumentException("PAGE_TOKEN_INVALID");
        }
        int bodyLength = 1 + 16 + 16 + 8 + 4 + 8;
        if (token.length != bodyLength + 32) throw new IllegalArgumentException("PAGE_TOKEN_INVALID");
        byte[] body = java.util.Arrays.copyOf(token, bodyLength);
        byte[] suppliedMac = java.util.Arrays.copyOfRange(token, bodyLength, token.length);
        if (!MessageDigest.isEqual(hmac(body), suppliedMac)) throw new SecurityException("PAGE_TOKEN_TAMPERED");
        ByteBuffer input = ByteBuffer.wrap(body);
        if (input.get() != TOKEN_VERSION) throw new IllegalArgumentException("PAGE_TOKEN_VERSION_UNSUPPORTED");
        byte[] collectionHash = new byte[16]; input.get(collectionHash);
        byte[] scopeHash = new byte[16]; input.get(scopeHash);
        long tokenRevision = input.getLong();
        int offset = input.getInt();
        long expiresAt = input.getLong();
        if (!MessageDigest.isEqual(collectionHash, hash16(collection))) {
            throw new SecurityException("PAGE_TOKEN_COLLECTION_MISMATCH");
        }
        if (!MessageDigest.isEqual(scopeHash, hash16(scopeKey))) {
            throw new SecurityException("PAGE_TOKEN_SCOPE_MISMATCH");
        }
        if (tokenRevision != revision) throw new IllegalStateException("PAGE_TOKEN_STALE");
        if (expiresAt < System.nanoTime()) throw new IllegalArgumentException("PAGE_TOKEN_EXPIRED");
        if (offset < 0 || offset > collectionSize) throw new IllegalArgumentException("PAGE_TOKEN_OFFSET_INVALID");
        return offset;
    }

    private byte[] hmac(byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] hash16(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.Arrays.copyOf(digest, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static long revision(List<? extends Parcelable> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
            for (Object value : values) stableDigest(digest, value, visited);
            long revision = ByteBuffer.wrap(digest.digest()).getLong() & Long.MAX_VALUE;
            return revision == 0L ? 1L : revision;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("PAGE_REVISION_FAILED", error);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void stableDigest(MessageDigest digest, Object value,
            IdentityHashMap<Object, Boolean> visited) throws ReflectiveOperationException {
        if (value == null) { digest.update((byte) 0); return; }
        Class<?> type = value.getClass();
        digest.update(type.getName().getBytes(StandardCharsets.UTF_8));
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Character || type.isEnum()) {
            digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8)); return;
        }
        if (value instanceof byte[] bytes) { digest.update(bytes); return; }
        if (type.isArray()) {
            int length = Array.getLength(value);
            digest.update(ByteBuffer.allocate(4).putInt(length).array());
            for (int index = 0; index < length; index++) stableDigest(digest, Array.get(value, index), visited);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) stableDigest(digest, item, visited);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            ArrayList<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(item -> String.valueOf(item.getKey())));
            for (Map.Entry<?, ?> item : entries) {
                stableDigest(digest, item.getKey(), visited);
                stableDigest(digest, item.getValue(), visited);
            }
            return;
        }
        if (visited.put(value, Boolean.TRUE) != null) { digest.update((byte) 1); return; }
        ArrayList<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) fields.add(field);
            }
        }
        fields.sort(Comparator.comparing(Field::getName));
        for (Field field : fields) {
            field.setAccessible(true);
            digest.update(field.getName().getBytes(StandardCharsets.UTF_8));
            stableDigest(digest, field.get(value), visited);
        }
        visited.remove(value);
    }

    private static int stringBytes(String value) { return value == null ? 4 : value.length() * 2 + 8; }
}
