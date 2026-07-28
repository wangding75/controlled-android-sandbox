package com.warden.controlledsandbox.framework.activity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Android-independent, bounded snapshot of an Activity result Intent. */
public record ResultIntentSnapshot(
        String action,
        String dataUri,
        String mimeType,
        String componentName,
        int flags,
        String clipDescription,
        Map<String, String> extras) {
    public static final int MAX_EXTRAS = 64;
    public static final ResultIntentSnapshot EMPTY = new ResultIntentSnapshot(
            "", "", "", "", 0, "", Map.of());

    public ResultIntentSnapshot {
        action = optional(action, "action", 1024);
        dataUri = optional(dataUri, "dataUri", 4096);
        mimeType = optional(mimeType, "mimeType", 255);
        componentName = optional(componentName, "componentName", 512);
        clipDescription = optional(clipDescription, "clipDescription", 1024);
        Map<String, String> bounded = new LinkedHashMap<>();
        Map<String, String> source = extras == null ? Map.of() : extras;
        if (source.size() > MAX_EXTRAS) {
            throw new IllegalArgumentException("too many Activity result extras");
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = required(entry.getKey(), "extra key", 256);
            String value = optional(entry.getValue(), "extra value", 4096);
            if (bounded.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate Activity result extra: " + key);
            }
        }
        extras = Map.copyOf(bounded);
    }

    public boolean isEmpty() {
        return action.isEmpty() && dataUri.isEmpty() && mimeType.isEmpty()
                && componentName.isEmpty() && flags == 0 && clipDescription.isEmpty()
                && extras.isEmpty();
    }

    private static String required(String value, String name, int maximum) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return normalized;
    }

    private static String optional(String value, String name, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " characters");
        }
        return normalized;
    }
}
