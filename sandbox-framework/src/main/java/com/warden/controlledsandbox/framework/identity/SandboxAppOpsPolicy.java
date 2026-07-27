package com.warden.controlledsandbox.framework.identity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable AppOps mode overrides carried with one Guest runtime generation. */
public final class SandboxAppOpsPolicy {
    public static final String DEFAULT = "DEFAULT";
    public static final String ALLOWED = "ALLOWED";
    public static final String IGNORED = "IGNORED";
    public static final String ERRORED = "ERRORED";

    private final Map<String, String> modes;

    public SandboxAppOpsPolicy(Map<String, String> modes) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (modes != null) {
            for (Map.Entry<String, String> item : modes.entrySet()) {
                String name = item.getKey() == null ? "" : item.getKey().trim();
                if (name.isEmpty()) continue;
                String value = normalize(item.getValue());
                if (!DEFAULT.equals(value)) normalized.put(name, value);
            }
        }
        this.modes = Collections.unmodifiableMap(normalized);
    }

    public Map<String, String> modes() { return modes; }
    public String mode(String opName) { return modes.getOrDefault(opName, DEFAULT); }
    public int modeCode(String opName) {
        return switch (mode(opName)) {
            case ALLOWED -> 0;
            case IGNORED -> 1;
            case ERRORED -> 2;
            default -> 3;
        };
    }

    private static String normalize(String value) {
        String normalized = value == null ? DEFAULT : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of(DEFAULT, ALLOWED, IGNORED, ERRORED).contains(normalized)) {
            throw new IllegalArgumentException("Unsupported AppOps mode: " + value);
        }
        return normalized;
    }
}
