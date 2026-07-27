package com.warden.controlledsandbox.framework.identity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Thread-safe permission decisions and effective host-backed grants for one Guest generation. */
public final class VirtualPermissionPolicy {
    public static final String DEFAULT = "DEFAULT";
    public static final String GRANTED = "GRANTED";
    public static final String DENIED = "DENIED";

    private volatile State state = new State(Set.of(), Map.of(), Set.of());

    /** Compatibility constructor: DEFAULT/GRANTED are treated as effective grants. */
    public VirtualPermissionPolicy(Set<String> declaredPermissions, Map<String, String> decisions) {
        replace(declaredPermissions, decisions, legacyEffective(declaredPermissions, decisions));
    }

    public VirtualPermissionPolicy(Set<String> declaredPermissions, Map<String, String> decisions,
                                   Set<String> effectiveGrants) {
        replace(declaredPermissions, decisions, effectiveGrants);
    }

    public void replace(Set<String> declaredPermissions, Map<String, String> decisions,
                        Set<String> effectiveGrants) {
        Set<String> declared = Collections.unmodifiableSet(new LinkedHashSet<>(
                declaredPermissions == null ? Set.of() : declaredPermissions));
        Map<String, String> normalized = new LinkedHashMap<>();
        if (decisions != null) {
            for (Map.Entry<String, String> item : decisions.entrySet()) {
                if (!declared.contains(item.getKey())) continue;
                String value = normalize(item.getValue());
                if (!DEFAULT.equals(value)) normalized.put(item.getKey(), value);
            }
        }
        LinkedHashSet<String> grants = new LinkedHashSet<>();
        if (effectiveGrants != null) {
            for (String permission : effectiveGrants) {
                if (declared.contains(permission)
                        && !DENIED.equals(normalized.getOrDefault(permission, DEFAULT))) {
                    grants.add(permission);
                }
            }
        }
        state = new State(declared, Collections.unmodifiableMap(normalized),
                Collections.unmodifiableSet(grants));
    }

    public Set<String> declaredPermissions() { return state.declaredPermissions; }
    public Map<String, String> decisions() { return state.decisions; }
    public Set<String> effectiveGrants() { return state.effectiveGrants; }

    public String decision(String permission) {
        State current = state;
        if (!current.declaredPermissions.contains(permission)) return DENIED;
        return current.decisions.getOrDefault(permission, DEFAULT);
    }

    public boolean isGranted(String permission) {
        State current = state;
        return current.declaredPermissions.contains(permission)
                && current.effectiveGrants.contains(permission);
    }

    private static Set<String> legacyEffective(Set<String> declared, Map<String, String> decisions) {
        LinkedHashSet<String> output = new LinkedHashSet<>();
        if (declared == null) return output;
        for (String permission : declared) {
            String decision = decisions == null ? DEFAULT : normalize(decisions.get(permission));
            if (!DENIED.equals(decision)) output.add(permission);
        }
        return output;
    }

    private static String normalize(String value) {
        String normalized = value == null ? DEFAULT : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of(DEFAULT, GRANTED, DENIED).contains(normalized)) {
            throw new IllegalArgumentException("Unsupported permission decision: " + value);
        }
        return normalized;
    }

    private record State(Set<String> declaredPermissions, Map<String, String> decisions,
                         Set<String> effectiveGrants) { }
}
