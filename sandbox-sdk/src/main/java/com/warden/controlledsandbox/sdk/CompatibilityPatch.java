package com.warden.controlledsandbox.sdk;

/** App-specific compatibility is isolated, version-gated and disabled until explicitly enabled. */
public interface CompatibilityPatch {
    String id();
    boolean matches(CompatibilityContext context);
    String reason();
    String whyNotGeneral();
}
