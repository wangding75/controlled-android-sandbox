package com.warden.controlledsandbox.sdk;

/** Auditable patch decision. Disabled is the safe default. */
public record CompatibilityDecision(String patchId, boolean enabled, String reason,
                                     String whyNotGeneral) {
    public CompatibilityDecision {
        patchId = patchId == null ? "" : patchId;
        reason = reason == null ? "" : reason;
        whyNotGeneral = whyNotGeneral == null ? "" : whyNotGeneral;
    }

    public static CompatibilityDecision disabled(String reason) {
        return new CompatibilityDecision("", false, reason, "No app-specific behavior enabled");
    }
}
