package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.SandboxError;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;

/** Stable typed error mapping for Activity Result Binder operations. */
public final class ActivityResultContractFailure {
    private ActivityResultContractFailure() { }

    public static ActivityResultResult from(ActivityResultRequest request, Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String code;
        if (root instanceof SecurityException) code = "ACTIVITY_RESULT_FORBIDDEN";
        else if (root instanceof IllegalArgumentException) code = "ACTIVITY_RESULT_INVALID_REQUEST";
        else if (root instanceof IllegalStateException) code = "ACTIVITY_RESULT_STATE_CONFLICT";
        else code = "ACTIVITY_RESULT_INTERNAL_ERROR";
        String message = root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage());
        if (message.length() > 512) message = message.substring(0, 512);
        return ActivityResultResult.failure(
                RuntimeProtocol.CURRENT,
                request == null ? "invalid-activity-result-request" : request.requestId(),
                new SandboxError(code, message, false));
    }
}
