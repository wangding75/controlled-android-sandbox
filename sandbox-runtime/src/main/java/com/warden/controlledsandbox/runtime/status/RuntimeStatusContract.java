package com.warden.controlledsandbox.runtime.status;

import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.SandboxError;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;

/** Validation and typed failures shared by the Binder implementation and tests. */
final class RuntimeStatusContract {
    static SandboxError validate(RuntimeStatusRequest request) {
        if (request == null) return new SandboxError("INVALID_REQUEST", "runtime status request is required", false);
        if (!RuntimeProtocol.isCompatible(request.protocolVersion())) {
            return new SandboxError("UNSUPPORTED_PROTOCOL",
                    "runtime protocol " + request.protocolVersion() + " is outside supported range "
                            + RuntimeProtocol.MIN_SUPPORTED + ".." + RuntimeProtocol.CURRENT,
                    false);
        }
        return null;
    }

    static RuntimeStatusResult failure(RuntimeStatusRequest request, SandboxError error) {
        String requestId = request == null ? "invalid-runtime-status-request" : request.requestId();
        return RuntimeStatusResult.failure(RuntimeProtocol.CURRENT, requestId, error);
    }

    static RuntimeStatusResult internalFailure(RuntimeStatusRequest request, Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage());
        if (message.length() > 512) message = message.substring(0, 512);
        return failure(request, new SandboxError("INTERNAL_ERROR", message, true));
    }

    private RuntimeStatusContract() { }
}
