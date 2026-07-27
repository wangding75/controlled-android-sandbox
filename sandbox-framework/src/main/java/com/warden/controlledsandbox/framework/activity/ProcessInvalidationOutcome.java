package com.warden.controlledsandbox.framework.activity;

/** Evidence for a non-recoverable Guest process-generation invalidation. */
public record ProcessInvalidationOutcome(
        int removedActivityCount,
        int revokedRouteCount) {

    public ProcessInvalidationOutcome {
        if (removedActivityCount < 0) {
            throw new IllegalArgumentException("removedActivityCount must be non-negative");
        }
        if (revokedRouteCount < 0) {
            throw new IllegalArgumentException("revokedRouteCount must be non-negative");
        }
    }
}
