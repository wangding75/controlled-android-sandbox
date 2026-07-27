package com.warden.controlledsandbox.framework.core;

import java.time.Instant;
import java.util.Objects;

public record ProxyEvent(
        Instant occurredAt,
        String service,
        String method,
        String action,
        boolean success,
        String detail) {

    public ProxyEvent {
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        service = Objects.requireNonNull(service, "service");
        method = Objects.requireNonNull(method, "method");
        action = Objects.requireNonNull(action, "action");
        detail = detail == null ? "" : detail;
    }

    public static ProxyEvent now(
            String service,
            String method,
            String action,
            boolean success,
            String detail) {
        return new ProxyEvent(Instant.now(), service, method, action, success, detail);
    }
}
