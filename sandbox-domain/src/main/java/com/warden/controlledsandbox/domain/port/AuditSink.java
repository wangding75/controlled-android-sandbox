package com.warden.controlledsandbox.domain.port;

/** External audit boundary for security-sensitive use cases. */
@FunctionalInterface
public interface AuditSink {
    enum Outcome { SUCCESS, REJECTED, FAILURE }

    void record(String category, String action, Outcome outcome, String detail);
}
