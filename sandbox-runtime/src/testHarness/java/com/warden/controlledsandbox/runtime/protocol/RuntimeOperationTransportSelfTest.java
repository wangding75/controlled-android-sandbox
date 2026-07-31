package com.warden.controlledsandbox.runtime.protocol;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;

/** Regression evidence for the typed V2 runtime envelope and legacy Bundle adapter. */
public final class RuntimeOperationTransportSelfTest {
    public static void main(String[] args) throws Exception {
        Bundle payload = payload();
        RuntimeOperationRequest request = RuntimeOperationTransport.request(
                RuntimeOperationRequest.INVOKE_COMPONENT, payload);
        require(request.protocolVersion() == 3, "protocol projected");
        require("guest.pkg".equals(request.packageName()) && request.virtualUserId() == 2,
                "top-level package identity projected");
        require("session-1".equals(request.sessionId()) && request.generation() == 7L,
                "top-level session identity projected");
        payload.putString(RuntimeKeys.PACKAGE_NAME, "host.pkg");
        require("guest.pkg".equals(request.payload().getString(RuntimeKeys.PACKAGE_NAME)),
                "request payload is defensive");

        Bundle successPayload = new Bundle();
        successPayload.putString(RuntimeKeys.STATUS, "COMPONENT_INVOKED");
        RuntimeOperationResult success = RuntimeOperationTransport.fromLegacy(request, successPayload);
        require(success.successful() && "COMPONENT_INVOKED".equals(success.status()),
                "legacy success is typed");
        require("COMPONENT_INVOKED".equals(RuntimeOperationTransport.toLegacyBundle(success)
                .getString(RuntimeKeys.STATUS)), "typed success returns legacy-compatible payload");

        Bundle failedPayload = new Bundle();
        failedPayload.putString(RuntimeKeys.STATUS, "FAILED");
        failedPayload.putString(RuntimeKeys.ERROR_TYPE, "BIND_UNAVAILABLE");
        failedPayload.putString(RuntimeKeys.ERROR_MESSAGE, "fixture");
        RuntimeOperationResult failed = RuntimeOperationTransport.fromLegacy(request, failedPayload);
        require(!failed.successful() && failed.error().retryable(),
                "stable retryable error is projected");

        RuntimeOperationResult remote = RuntimeOperationTransport.execute(
                new FakeBroker(false), RuntimeOperationRequest.INVOKE_COMPONENT, payload());
        require(remote.successful() && "REMOTE_OK".equals(remote.status()),
                "typed remote call correlates request and result");

        boolean mismatchDenied = false;
        try {
            RuntimeOperationTransport.execute(
                    new FakeBroker(true), RuntimeOperationRequest.INVOKE_COMPONENT, payload());
        } catch (SecurityException expected) {
            mismatchDenied = "RUNTIME_OPERATION_CORRELATION_MISMATCH".equals(expected.getMessage());
        }
        require(mismatchDenied, "mismatched response correlation is rejected");

        boolean unsupportedDenied = false;
        try {
            new RuntimeOperationRequest(3, "request", "UNKNOWN", "guest.pkg", 2,
                    "session-1", 7L, new Bundle());
        } catch (IllegalArgumentException expected) {
            unsupportedDenied = expected.getMessage().contains("unsupported runtime operation");
        }
        require(unsupportedDenied, "operation allowlist is enforced");
        System.out.println("PASS typed runtime-operation transport self-test");
    }

    private static Bundle payload() {
        Bundle payload = new Bundle();
        payload.putInt(RuntimeKeys.PROTOCOL, 3);
        payload.putString(RuntimeKeys.PACKAGE_NAME, "guest.pkg");
        payload.putInt(RuntimeKeys.VIRTUAL_USER_ID, 2);
        payload.putString(RuntimeKeys.SESSION_ID, "session-1");
        payload.putLong(RuntimeKeys.GENERATION, 7L);
        return payload;
    }

    private static final class FakeBroker extends IRuntimeBroker.Stub {
        private final boolean mismatch;
        FakeBroker(boolean mismatch) { this.mismatch = mismatch; }

        @Override public RuntimeOperationResult executeV2(RuntimeOperationRequest request) {
            RuntimeOperationRequest responseRequest = mismatch
                    ? new RuntimeOperationRequest(request.protocolVersion(), "different-request",
                            request.operation(), request.packageName(), request.virtualUserId(),
                            request.sessionId(), request.generation(), request.payload())
                    : request;
            Bundle result = new Bundle();
            result.putString(RuntimeKeys.STATUS, "REMOTE_OK");
            return RuntimeOperationResult.success(responseRequest, "REMOTE_OK", result);
        }
        @Override public ActivityTaskResult activityTaskOperation(ActivityTaskRequest request) { throw legacy(); }
        @Override public ActivityResultResult activityResultOperation(ActivityResultRequest request) { throw legacy(); }
        @Override public PackageServiceResult requestRuntimePermission(String sessionId, long generation,
                String permission, int requestCode) { throw legacy(); }
        @Override public PackageServiceResult reportRuntimePermissionResult(String sessionId, long generation,
                String permission, int requestCode, boolean hostGranted, String reason) { throw legacy(); }
        @Override public RuntimeStatusResult runtimeStatusV2(RuntimeStatusRequest request) { throw legacy(); }
        @Override public void stopGuest(String packageName, int virtualUserId) { throw legacy(); }

        private static UnsupportedOperationException legacy() {
            return new UnsupportedOperationException("legacy runtime path must not be called");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
