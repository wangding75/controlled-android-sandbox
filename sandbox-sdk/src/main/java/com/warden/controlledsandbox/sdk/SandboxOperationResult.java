package com.warden.controlledsandbox.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable adapter result; runtime Bundle and Binder details do not cross this boundary. */
public record SandboxOperationResult(boolean successful, String operation, String status,
                                     String errorCode, String errorMessage,
                                     SandboxIdentity identity, Map<String, String> diagnostics) {
    public SandboxOperationResult {
        operation = required(operation, "operation");
        status = status == null ? "" : status;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        diagnostics = Map.copyOf(new LinkedHashMap<>(diagnostics == null ? Map.of() : diagnostics));
        if (successful && (!errorCode.isEmpty() || !errorMessage.isEmpty())) {
            throw new IllegalArgumentException("successful result cannot contain an error");
        }
        if (!successful && errorCode.isEmpty()) throw new IllegalArgumentException("failed result needs errorCode");
    }

    public static SandboxOperationResult success(String operation, String status,
                                                 SandboxIdentity identity,
                                                 Map<String, String> diagnostics) {
        return new SandboxOperationResult(true, operation, status, "", "", identity, diagnostics);
    }

    public static SandboxOperationResult failure(String operation, String code, String message,
                                                 SandboxIdentity identity,
                                                 Map<String, String> diagnostics) {
        return new SandboxOperationResult(false, operation, "FAILED", code, message, identity, diagnostics);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
