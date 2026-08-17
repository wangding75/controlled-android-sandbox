package com.warden.controlledsandbox.framework.activity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Arrays;

/**
 * Bounded, transport-neutral Activity saved state.
 *
 * <p>The string map is retained for old/legacy component routes and diagnostics. Framework-owned
 * ActivityThread callbacks additionally carry opaque Parcel payloads. The Broker never
 * unparcels Guest objects; it only fences, persists and replays the bytes to the same Guest
 * class loader after a process generation change.</p>
 */
public record SavedActivityState(
        long version,
        Map<String, String> values,
        byte[] bundlePayload,
        byte[] persistableBundlePayload) {
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_VALUE_LENGTH = 8192;
    private static final int MAX_TOTAL_CHARACTERS = 262_144;
    /** Keep the opaque state below the Binder route/checkpoint envelope limit. */
    public static final int MAX_PAYLOAD_BYTES = 384 * 1024;

    /** Compatibility constructor for the legacy string-only component controller. */
    public SavedActivityState(long version, Map<String, String> values) {
        this(version, values, null, null);
    }

    public SavedActivityState {
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        Objects.requireNonNull(values, "values");
        if (values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("saved state exceeds entry limit");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        int totalCharacters = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = requireText(entry.getKey(), "saved-state key");
            String value = Objects.requireNonNull(entry.getValue(), "saved-state value");
            if (key.length() > MAX_KEY_LENGTH) {
                throw new IllegalArgumentException("saved-state key is too long");
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("saved-state value is too long");
            }
            totalCharacters = Math.addExact(totalCharacters, key.length() + value.length());
            if (totalCharacters > MAX_TOTAL_CHARACTERS) {
                throw new IllegalArgumentException("saved state exceeds aggregate size limit");
            }
            copy.put(key, value);
        }
        values = Map.copyOf(copy);
        bundlePayload = copyPayload(bundlePayload, "bundle saved-state payload");
        persistableBundlePayload = copyPayload(
                persistableBundlePayload, "persistable saved-state payload");
        if (bundlePayload.length + persistableBundlePayload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("saved-state payload exceeds aggregate size limit");
        }
    }

    @Override public byte[] bundlePayload() {
        return bundlePayload.clone();
    }

    @Override public byte[] persistableBundlePayload() {
        return persistableBundlePayload.clone();
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SavedActivityState that)) return false;
        return version == that.version
                && values.equals(that.values)
                && Arrays.equals(bundlePayload, that.bundlePayload)
                && Arrays.equals(persistableBundlePayload, that.persistableBundlePayload);
    }

    @Override public int hashCode() {
        int result = Long.hashCode(version);
        result = 31 * result + values.hashCode();
        result = 31 * result + Arrays.hashCode(bundlePayload);
        result = 31 * result + Arrays.hashCode(persistableBundlePayload);
        return result;
    }

    private static byte[] copyPayload(byte[] value, String name) {
        if (value == null || value.length == 0) return new byte[0];
        if (value.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(name + " is too large");
        }
        return value.clone();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
