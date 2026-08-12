package com.warden.controlledsandbox.runtime.guest;

import android.os.RemoteException;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.SandboxError;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Synchronous, typed and fail-closed client for Broker-owned virtual Activity tasks. */
final class GuestActivityTaskClient {
    private final GuestPackageSpec spec;
    private final IRuntimeBroker broker;
    private final AtomicLong requestSequence = new AtomicLong();

    GuestActivityTaskClient(GuestPackageSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
        broker = IRuntimeBroker.Stub.asInterface(spec.runtimeBrokerBinder);
        if (broker == null) throw new IllegalStateException("RUNTIME_BROKER_CAPABILITY_INVALID");
    }

    ActivityTaskResult query(String operation, int maxCount) {
        return execute(operation, 0, maxCount, "");
    }

    ActivityTaskResult mutateTask(String operation, int taskId) {
        return execute(operation, taskId, 0, "");
    }

    ActivityTaskResult mutateActivity(String operation, String activityToken) {
        return execute(operation, 0, 0, activityToken);
    }

    boolean isRootActivity(String activityToken) {
        return execute(ActivityTaskRequest.QUERY_ACTIVITY_ROOT, 0, 0, activityToken).changed();
    }

    private ActivityTaskResult execute(String operation, int taskId, int maxCount,
                                       String activityToken) {
        String requestId = "guest-task-" + spec.generation + "-"
                + requestSequence.incrementAndGet();
        ActivityTaskRequest request = new ActivityTaskRequest(
                spec.protocol, requestId, spec.sessionId, spec.generation,
                spec.virtualUserId, spec.packageName, operation, taskId, maxCount,
                activityToken == null ? "" : activityToken);
        final ActivityTaskResult result;
        try {
            result = broker.activityTaskOperation(request);
        } catch (RemoteException error) {
            throw new IllegalStateException("VIRTUAL_TASK_BROKER_UNAVAILABLE", error);
        }
        if (result == null) throw new IllegalStateException("VIRTUAL_TASK_BROKER_EMPTY_RESULT");
        if (!result.successful()) {
            SandboxError error = result.error();
            String code = error == null ? "VIRTUAL_TASK_OPERATION_FAILED" : error.code();
            String message = error == null ? "" : error.message();
            throw new IllegalStateException(code + (message.isEmpty() ? "" : ":" + message));
        }
        if (!requestId.equals(result.requestId()) || !operation.equals(result.operation())) {
            throw new SecurityException("VIRTUAL_TASK_RESPONSE_IDENTITY_MISMATCH");
        }
        return result;
    }
}
