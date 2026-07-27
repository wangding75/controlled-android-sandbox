package com.warden.controlledsandbox.framework.activity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, transport-neutral saved-state metadata. Binary payloads stay in broker-owned storage. */
public record SavedActivityState(long version, Map<String, String> values) {
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_VALUE_LENGTH = 8192;
    private static final int MAX_TOTAL_CHARACTERS = 262_144;

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
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
