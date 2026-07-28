package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.SandboxError;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;

/** Stable typed error mapping for the Activity/Task Binder contract. */
public final class ActivityTaskContractFailure {
    private ActivityTaskContractFailure() { }

    public static ActivityTaskResult from(ActivityTaskRequest request, Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String code;
        if (root instanceof SecurityException) code = "ACTIVITY_TASK_FORBIDDEN";
        else if (root instanceof IllegalArgumentException) code = "ACTIVITY_TASK_INVALID_REQUEST";
        else if (root instanceof IllegalStateException) code = "ACTIVITY_TASK_STATE_CONFLICT";
        else code = "ACTIVITY_TASK_INTERNAL_ERROR";
        String message = root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage());
        if (message.length() > 512) message = message.substring(0, 512);
        return ActivityTaskResult.failure(
                RuntimeProtocol.CURRENT,
                request == null ? "invalid-activity-task-request" : request.requestId(),
                new SandboxError(code, message, false));
    }
}
