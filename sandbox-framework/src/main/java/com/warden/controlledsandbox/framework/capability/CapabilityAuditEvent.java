package com.warden.controlledsandbox.framework.capability;

/** Immutable source-side audit record for one protected capability call. */
public record CapabilityAuditEvent(long sequence, String capability, String service,
                                   String operation, String decision, String detail) {
    public CapabilityAuditEvent {
        if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
        capability = value(capability);
        service = value(service);
        operation = value(operation);
        decision = value(decision);
        detail = detail == null ? "" : detail.trim();
    }

    private static String value(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("value is required");
        return value.trim();
    }
}
