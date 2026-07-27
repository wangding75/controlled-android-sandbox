package com.warden.controlledsandbox.framework.identity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Thread-safe AppOps mode overrides carried by one Guest runtime generation. */
public final class SandboxAppOpsPolicy {
    public static final String DEFAULT = "DEFAULT";
    public static final String ALLOWED = "ALLOWED";
    public static final String IGNORED = "IGNORED";
    public static final String ERRORED = "ERRORED";

    private volatile Map<String, String> modes = Map.of();

    public SandboxAppOpsPolicy(Map<String, String> modes) { replace(modes); }

    public synchronized void replace(Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, String> item : values.entrySet()) {
                String name = item.getKey() == null ? "" : item.getKey().trim();
                if (name.isEmpty()) continue;
                String value = normalize(item.getValue());
                if (!DEFAULT.equals(value)) normalized.put(name, value);
            }
        }
        modes = Collections.unmodifiableMap(normalized);
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
