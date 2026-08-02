package com.warden.controlledsandbox.runtime.status;

import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.RuntimeStatusSnapshot;
import com.warden.controlledsandbox.contract.SandboxError;
import com.warden.controlledsandbox.domain.port.AuditSink;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import java.util.function.LongConsumer;

/** Typed use-case dispatcher isolated from Android Binder and concrete Broker registries. */
public final class RuntimeStatusDispatcher {
    private static final String CATEGORY = "runtime";
    private static final String ACTION = "status";

    private final Clock clock;
    private final RuntimeStatusSource source;
    private final LongConsumer maintenance;
    private final AuditSink auditSink;

    public RuntimeStatusDispatcher(Clock clock, RuntimeStatusSource source,
                                   LongConsumer maintenance, AuditSink auditSink) {
        if (clock == null || source == null || maintenance == null || auditSink == null) {
            throw new IllegalArgumentException("runtime status dependencies are required");
        }
        this.clock = clock;
        this.source = source;
        this.maintenance = maintenance;
        this.auditSink = auditSink;
    }

    public RuntimeStatusResult dispatch(RuntimeStatusRequest request) {
        SandboxError validation = RuntimeStatusContract.validate(request);
        if (validation != null) {
            RuntimeStatusResult result = RuntimeStatusContract.failure(request, validation);
            audit(AuditSink.Outcome.REJECTED, validation.code());
            return result;
        }
        try {
            long nowMs = clock.nowMillis();
            if (nowMs < 0) throw new IllegalStateException("CLOCK_RETURNED_NEGATIVE_TIME");
            maintenance.accept(nowMs);
            RuntimeStatusSnapshot snapshot = source.snapshot(nowMs);
            if (snapshot == null) throw new IllegalStateException("RUNTIME_STATUS_SOURCE_RETURNED_NULL");
            RuntimeStatusResult result = RuntimeStatusResult.success(
                    RuntimeProtocol.CURRENT,
                    request.requestId(),
                    "RUNTIME_M3_DEVELOPMENT",
                    "VIRTUAL_INSTANCES_APPLICATION_COMPONENT_BRIDGES_NATIVE_POLICY_DIAGNOSTICS",
                    "Activity bridge uses reflected framework fields and requires emulator validation per API level",
                    snapshot);
            audit(AuditSink.Outcome.SUCCESS, request.requestId());
            return result;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            RuntimeStatusResult result = RuntimeStatusContract.internalFailure(request, error);
            audit(AuditSink.Outcome.FAILURE, result.error().code());
            return result;
        }
    }

    private void audit(AuditSink.Outcome outcome, String detail) {
        try { auditSink.record(CATEGORY, ACTION, outcome, detail == null ? "" : detail); }
        catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); /* Audit failure must not alter the use-case result. */ }
    }
}
