package com.warden.controlledsandbox.framework.routing;

import java.util.Objects;

/** Opaque token safe to place in a Host Stub Activity Intent. */
public record RouteToken(String value, long expiresAtMillis) {
    public RouteToken {
        String normalized = Objects.requireNonNull(value, "value").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        value = normalized;
        if (expiresAtMillis < 1) {
            throw new IllegalArgumentException("expiresAtMillis must be positive");
        }
    }
}
