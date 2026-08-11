package com.warden.controlledsandbox.runtime.protocol;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.IGuestProcess;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.contract.SandboxError;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import java.util.UUID;

/** Bridges typed runtime envelopes to the bounded legacy component payload during migration. */
public final class RuntimeOperationTransport {
    private RuntimeOperationTransport() { }

    public static RuntimeOperationRequest request(String operation, Bundle payload) {
        Bundle copy = payload == null ? new Bundle() : new Bundle(payload);
        int protocol = copy.getInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
        String packageName = copy.getString(RuntimeKeys.PACKAGE_NAME, "");
        int virtualUserId = copy.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        String sessionId = copy.getString(RuntimeKeys.SESSION_ID, "");
        long generation = copy.getLong(RuntimeKeys.GENERATION, 0L);
        return new RuntimeOperationRequest(protocol, UUID.randomUUID().toString(), operation,
                packageName, virtualUserId, sessionId, generation, copy);
    }

    public static RuntimeOperationResult execute(
            IRuntimeBroker broker, String operation, Bundle payload) throws Exception {
        if (broker == null) throw new IllegalArgumentException("broker is required");
        RuntimeOperationRequest request = request(operation, payload);
        RuntimeOperationResult result = broker.executeV2(request);
        return requireMatching(request, result);
    }

    public static RuntimeOperationResult execute(
            IGuestProcess guest, String operation, Bundle payload) throws Exception {
        if (guest == null) throw new IllegalArgumentException("guest is required");
        RuntimeOperationRequest request = request(operation, payload);
        RuntimeOperationResult result = guest.executeV2(request);
        return requireMatching(request, result);
    }

    public static RuntimeOperationResult fromLegacy(
            RuntimeOperationRequest request, Bundle legacy) {
        if (request == null) throw new IllegalArgumentException("request is required");
        Bundle payload = legacy == null ? new Bundle() : new Bundle(legacy);
        String status = payload.getString(RuntimeKeys.STATUS, "");
        if (status.isEmpty()) status = "OK";
        String errorType = payload.getString(RuntimeKeys.ERROR_TYPE, "");
        if ("FAILED".equals(status) || !errorType.isEmpty()) {
            String code = errorType.isEmpty() ? "RUNTIME_OPERATION_FAILED" : boundedCode(errorType);
            String message = payload.getString(RuntimeKeys.ERROR_MESSAGE, status);
            return RuntimeOperationResult.failure(request, status,
                    new SandboxError(code, boundedMessage(message), retryable(code)), payload);
        }
        return RuntimeOperationResult.success(request, status, payload);
    }

    public static RuntimeOperationResult failure(
            RuntimeOperationRequest request, Throwable error) {
        Bundle payload = new Bundle();
        String code = error == null ? "RUNTIME_OPERATION_FAILED"
                : boundedCode(error.getClass().getSimpleName());
        String message = error == null ? "Unknown runtime operation failure"
                : String.valueOf(error.getMessage());
        payload.putString(RuntimeKeys.STATUS, "FAILED");
        payload.putString(RuntimeKeys.ERROR_TYPE, code);
        payload.putString(RuntimeKeys.ERROR_MESSAGE, message);
        return RuntimeOperationResult.failure(request, "FAILED",
                new SandboxError(code, boundedMessage(message), retryable(code)), payload);
    }

    public static Bundle toLegacyBundle(RuntimeOperationResult result) {
        if (result == null) {
            Bundle failed = new Bundle();
            failed.putString(RuntimeKeys.STATUS, "FAILED");
            failed.putString(RuntimeKeys.ERROR_TYPE, "EMPTY_RUNTIME_OPERATION_RESULT");
            failed.putString(RuntimeKeys.ERROR_MESSAGE, "Runtime operation returned no result");
            return failed;
        }
        Bundle payload = result.payload();
        payload.putString(RuntimeKeys.STATUS, result.status());
        if (!result.successful()) {
            SandboxError error = result.error();
            payload.putString(RuntimeKeys.ERROR_TYPE,
                    error == null ? "RUNTIME_OPERATION_FAILED" : error.code());
            payload.putString(RuntimeKeys.ERROR_MESSAGE,
                    error == null ? "Runtime operation failed" : error.message());
        }
        return payload;
    }

    private static RuntimeOperationResult requireMatching(
            RuntimeOperationRequest request, RuntimeOperationResult result) {
        if (result == null) throw new IllegalStateException("RUNTIME_OPERATION_EMPTY_RESULT");
        if (result.protocolVersion() != request.protocolVersion()
                || !result.requestId().equals(request.requestId())
                || !result.operation().equals(request.operation())) {
            throw new SecurityException("RUNTIME_OPERATION_CORRELATION_MISMATCH");
        }
        return result;
    }

    private static String boundedCode(String value) {
        String normalized = value == null ? "RUNTIME_OPERATION_FAILED"
                : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (normalized.isEmpty()) normalized = "RUNTIME_OPERATION_FAILED";
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static String boundedMessage(String value) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    private static boolean retryable(String code) {
        String normalized = code.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("TIMEOUT") || normalized.contains("BIND")
                || normalized.contains("UNAVAILABLE") || normalized.contains("BUSY");
    }
}
