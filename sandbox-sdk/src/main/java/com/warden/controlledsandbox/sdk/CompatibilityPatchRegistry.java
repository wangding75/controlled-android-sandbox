package com.warden.controlledsandbox.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Central patch gate: no package checks are allowed in Binder/framework/runtime code. */
public final class CompatibilityPatchRegistry {
    private final Map<String, CompatibilityPatch> patches = new LinkedHashMap<>();
    private final Map<String, Boolean> enabled = new LinkedHashMap<>();

    public synchronized void register(CompatibilityPatch patch) {
        Objects.requireNonNull(patch, "patch");
        if (patch.id() == null || patch.id().isBlank()) throw new IllegalArgumentException("patch id is required");
        if (patches.putIfAbsent(patch.id(), patch) != null) {
            throw new IllegalArgumentException("Duplicate compatibility patch: " + patch.id());
        }
        enabled.put(patch.id(), false);
    }

    public synchronized void enable(String patchId) {
        requirePatch(patchId);
        enabled.put(patchId, true);
    }

    public synchronized void disable(String patchId) {
        requirePatch(patchId);
        enabled.put(patchId, false);
    }

    public synchronized CompatibilityDecision decide(CompatibilityContext context) {
        Objects.requireNonNull(context, "context");
        for (CompatibilityPatch patch : patches.values()) {
            if (Boolean.TRUE.equals(enabled.get(patch.id())) && patch.matches(context)) {
                return new CompatibilityDecision(patch.id(), true, patch.reason(), patch.whyNotGeneral());
            }
        }
        return CompatibilityDecision.disabled("No explicitly enabled patch matches " + context.packageName());
    }

    public synchronized boolean isEnabled(String patchId) {
        return Boolean.TRUE.equals(enabled.get(patchId));
    }

    private void requirePatch(String patchId) {
        if (!patches.containsKey(patchId)) throw new IllegalArgumentException("Unknown compatibility patch: " + patchId);
    }
}
