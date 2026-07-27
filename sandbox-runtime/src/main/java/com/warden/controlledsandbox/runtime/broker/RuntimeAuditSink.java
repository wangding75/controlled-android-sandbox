package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;

import com.warden.controlledsandbox.domain.port.AuditSink;

/** Android diagnostics adapter for use-case audit events. */
public final class RuntimeAuditSink implements AuditSink {
    @Override public void record(String category, String action, Outcome outcome, String detail) {
        RuntimeEventLog.audit(category, action, outcome.name(), detail);
    }
}
