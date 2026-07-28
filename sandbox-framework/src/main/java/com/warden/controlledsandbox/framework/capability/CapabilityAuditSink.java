package com.warden.controlledsandbox.framework.capability;

/** Port used by framework proxies to report bounded capability decisions. */
@FunctionalInterface
public interface CapabilityAuditSink {
    CapabilityAuditSink NO_OP = event -> { };
    void record(CapabilityAuditEvent event);
}
