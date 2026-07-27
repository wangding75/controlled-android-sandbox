package com.warden.controlledsandbox.framework.core;

/** Low-volume diagnostics hook. Implementations must not throw. */
@FunctionalInterface
public interface ProxyTelemetry {
    ProxyTelemetry NO_OP = event -> { };

    void record(ProxyEvent event);
}
