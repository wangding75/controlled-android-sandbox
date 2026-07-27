package com.warden.controlledsandbox.domain.port;

/** Minimal read port used by diagnostics without exposing SessionRegistry internals. */
public interface SessionMetricsRepository {
    int capacity();
    int used();
    int count();
}
