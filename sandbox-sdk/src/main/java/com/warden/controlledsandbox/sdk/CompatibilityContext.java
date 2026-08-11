package com.warden.controlledsandbox.sdk;

import java.util.Set;

/** Versioned environment presented to a compatibility patch; no framework object is exposed. */
public record CompatibilityContext(String packageName, String versionName, long versionCode,
                                   Set<String> capabilities) {
    public CompatibilityContext {
        if (packageName == null || packageName.isBlank()) throw new IllegalArgumentException("packageName is required");
        if (versionCode < 0) throw new IllegalArgumentException("versionCode must be non-negative");
        versionName = versionName == null ? "" : versionName;
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
    }

    public boolean hasCapability(String capability) { return capabilities.contains(capability); }
}
