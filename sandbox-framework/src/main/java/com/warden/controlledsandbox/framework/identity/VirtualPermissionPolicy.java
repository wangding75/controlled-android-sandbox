package com.warden.controlledsandbox.framework.identity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Immutable permission decisions carried with one Guest runtime generation. */
public final class VirtualPermissionPolicy {
    public static final String DEFAULT = "DEFAULT";
    public static final String GRANTED = "GRANTED";
    public static final String DENIED = "DENIED";

    private final Set<String> declaredPermissions;
    private final Map<String, String> decisions;

    public VirtualPermissionPolicy(Set<String> declaredPermissions, Map<String, String> decisions) {
        this.declaredPermissions = Collections.unmodifiableSet(new LinkedHashSet<>(
                declaredPermissions == null ? Set.of() : declaredPermissions));
        Map<String, String> normalized = new LinkedHashMap<>();
        if (decisions != null) {
            for (Map.Entry<String, String> item : decisions.entrySet()) {
                if (!this.declaredPermissions.contains(item.getKey())) continue;
                String value = normalize(item.getValue());
                if (!DEFAULT.equals(value)) normalized.put(item.getKey(), value);
            }
        }
        this.decisions = Collections.unmodifiableMap(normalized);
    }

    public Set<String> declaredPermissions() { return declaredPermissions; }
    public Map<String, String> decisions() { return decisions; }
    public String decision(String permission) {
        if (!declaredPermissions.contains(permission)) return DENIED;
        return decisions.getOrDefault(permission, DEFAULT);
    }
    public boolean isGranted(String permission) {
        String decision = decision(permission);
        return GRANTED.equals(decision) || DEFAULT.equals(decision);
    }

    private static String normalize(String value) {
        String normalized = value == null ? DEFAULT : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of(DEFAULT, GRANTED, DENIED).contains(normalized)) {
            throw new IllegalArgumentException("Unsupported permission decision: " + value);
        }
        return normalized;
    }
}
