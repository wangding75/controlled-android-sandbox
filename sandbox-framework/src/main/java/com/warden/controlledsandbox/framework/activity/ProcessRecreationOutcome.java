package com.warden.controlledsandbox.framework.activity;

import java.util.List;
import java.util.Objects;

/** Evidence for a recoverable Guest process-generation transition. */
public record ProcessRecreationOutcome(
        List<ActivityRecreation> recreations,
        int revokedRouteCount) {

    public ProcessRecreationOutcome {
        recreations = List.copyOf(Objects.requireNonNull(recreations, "recreations"));
        if (revokedRouteCount < 0) {
            throw new IllegalArgumentException("revokedRouteCount must be non-negative");
        }
    }
}
