package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
import java.util.function.Consumer;

/** Synchronous, session-bound bridge used by standard Guest Context APIs. */
final class GuestRuntimeBrokerBridge {
    private final GuestPackageSpec spec;
    private final IRuntimeBroker broker;
    private final GuestMainThreadDispatcher mainThread;

    GuestRuntimeBrokerBridge(GuestPackageSpec spec, GuestMainThreadDispatcher mainThread) {
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.mainThread = java.util.Objects.requireNonNull(mainThread, "mainThread");
        this.broker = IRuntimeBroker.Stub.asInterface(spec.runtimeBrokerBinder);
    }

    Bundle invokeComponent(Bundle request) {
        logSubmission("SYNC", RuntimeOperationRequest.INVOKE_COMPONENT, request);
        return execute(RuntimeOperationRequest.INVOKE_COMPONENT, request);
    }

    void invokeComponentAsync(Bundle request, Consumer<Bundle> success,
                              Consumer<Throwable> failure) {
        logSubmission("ASYNC", RuntimeOperationRequest.INVOKE_COMPONENT, request);
        mainThread.callBrokerAsync(
                () -> execute(RuntimeOperationRequest.INVOKE_COMPONENT, request), success, failure);
    }

    Bundle launchActivity(Bundle request) {
        return execute(RuntimeOperationRequest.LAUNCH_ACTIVITY, request);
    }

    void launchActivityAsync(Bundle request, Consumer<Bundle> success,
                             Consumer<Throwable> failure) {
        logSubmission("ASYNC", RuntimeOperationRequest.LAUNCH_ACTIVITY, request);
        mainThread.callBrokerAsync(
                () -> execute(RuntimeOperationRequest.LAUNCH_ACTIVITY, request), success, failure);
    }

    Bundle launchActivityFromFrameworkHost(Bundle request) {
        Bundle payload = request == null ? baseRequest() : new Bundle(request);
        payload.putBoolean(RuntimeKeys.ACTIVITY_FRAMEWORK_HOST, true);
        return launchActivity(payload);
    }

    void grantUriPermission(String targetPackage, int targetVirtualUserId,
                            String uri, int modeFlags) {
        Bundle request = baseRequest();
        request.putString(RuntimeKeys.TARGET_PACKAGE_NAME, targetPackage);
        request.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, targetVirtualUserId);
        request.putString(RuntimeKeys.URI, uri);
        request.putInt(RuntimeKeys.URI_FLAGS, modeFlags);
        execute(RuntimeOperationRequest.GRANT_URI_PERMISSION, request);
    }

    void revokeUriPermission(String uri, int modeFlags) {
        Bundle request = baseRequest();
        request.putString(RuntimeKeys.URI, uri);
        request.putInt(RuntimeKeys.URI_FLAGS, modeFlags);
        execute(RuntimeOperationRequest.REVOKE_URI_PERMISSION, request);
    }

    int checkUriPermission(String uri, int pid, int uid, int modeFlags) {
        Bundle request = baseRequest();
        request.putString(RuntimeKeys.URI, uri);
        request.putInt(RuntimeKeys.URI_CHECK_PID, pid);
        request.putInt(RuntimeKeys.URI_CHECK_UID, uid);
        request.putInt(RuntimeKeys.URI_FLAGS, modeFlags);
        Bundle result = execute(RuntimeOperationRequest.CHECK_URI_PERMISSION, request);
        return result.getInt(RuntimeKeys.URI_PERMISSION_RESULT,
                android.content.pm.PackageManager.PERMISSION_DENIED);
    }

    Bundle openPackageResources(String targetPackage) {
        Bundle request = baseRequest();
        request.putString(RuntimeKeys.PACKAGE_RESOURCE_TARGET, targetPackage);
        return execute(RuntimeOperationRequest.OPEN_PACKAGE_RESOURCES, request);
    }

    Bundle baseRequest() {
        Bundle request = spec.toRuntimeRequestBundle();
        request.putString(RuntimeKeys.CALLER_PACKAGE_NAME, spec.packageName);
        request.putInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, spec.virtualUserId);
        request.putInt(RuntimeKeys.CALLER_VIRTUAL_UID, spec.virtualUid);
        request.putString(RuntimeKeys.CALLER_SESSION_ID, spec.sessionId);
        request.putLong(RuntimeKeys.CALLER_GENERATION, spec.generation);
        return request;
    }

    private void logSubmission(String mode, String operation, Bundle request) {
        String component = request == null ? "" : request.getString(RuntimeKeys.COMPONENT_CLASS, "");
        String componentOperation = request == null ? "" : request.getString(
                com.warden.controlledsandbox.runtime.protocol.ComponentOperations.OPERATION, "");
        android.util.Log.i("CS_BROKER_CALL", "SUBMIT mode=" + mode
                + " operation=" + operation
                + " component=" + component
                + " componentOp=" + componentOperation
                + " callerMain=" + mainThread.isMainThread()
                + " pid=" + Process.myPid()
                + " tid=" + Process.myTid()
                + " thread=" + Thread.currentThread().getName());
    }

    private Bundle execute(String operation, Bundle request) {
        long startedAt = SystemClock.elapsedRealtime();
        try {
            if (broker == null) throw new IllegalStateException("RUNTIME_BROKER_CAPABILITY_INVALID");
            Bundle payload = request == null ? baseRequest() : new Bundle(request);
            fillIdentity(payload);
            String component = payload.getString(RuntimeKeys.COMPONENT_CLASS, "");
            String componentOperation = payload.getString(
                    com.warden.controlledsandbox.runtime.protocol.ComponentOperations.OPERATION, "");
            android.util.Log.i("CS_BROKER_CALL", "BEGIN operation=" + operation
                    + " component=" + component
                    + " componentOp=" + componentOperation
                    + " main=" + mainThread.isMainThread()
                    + " pid=" + Process.myPid()
                    + " tid=" + Process.myTid());
            Bundle result = mainThread.callBroker(() -> RuntimeOperationTransport.toLegacyBundle(
                    RuntimeOperationTransport.execute(broker, operation, payload)));
            requireSuccess(result, operation);
            android.util.Log.i("CS_BROKER_CALL", "END operation=" + operation
                    + " componentOp=" + componentOperation
                    + " elapsedMs=" + Math.max(0L, SystemClock.elapsedRealtime() - startedAt)
                    + " status=" + result.getString(RuntimeKeys.STATUS, "")
                    + " pid=" + Process.myPid()
                    + " tid=" + Process.myTid());
            return result;
        } catch (RuntimeException error) {
            android.util.Log.e("CS_BROKER_CALL", "FAIL operation=" + operation
                    + " elapsedMs=" + Math.max(0L, SystemClock.elapsedRealtime() - startedAt)
                    + " error=" + error.getClass().getName() + ":" + error.getMessage(), error);
            throw error;
        } catch (Exception error) {
            android.util.Log.e("CS_BROKER_CALL", "FAIL operation=" + operation
                    + " elapsedMs=" + Math.max(0L, SystemClock.elapsedRealtime() - startedAt)
                    + " error=" + error.getClass().getName() + ":" + error.getMessage(), error);
            throw new IllegalStateException("RUNTIME_BROKER_CALL_FAILED:" + operation, error);
        }
    }

    private void fillIdentity(Bundle request) {
        // PROCESS_NAME is also the target process selector for component routes. Preserve a
        // manifest-declared :remote target supplied by GuestContextComponentRouter; caller
        // ownership remains in the dedicated CALLER_* fields below.
        String targetProcess = request.getString(RuntimeKeys.PROCESS_NAME, "");
        if (targetProcess == null || targetProcess.trim().isEmpty()) {
            targetProcess = spec.processName;
        }
        request.putInt(RuntimeKeys.PROTOCOL,
                com.warden.controlledsandbox.domain.protocol.RuntimeProtocol.CURRENT);
        request.putString(RuntimeKeys.PACKAGE_NAME, spec.packageName);
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, spec.virtualUserId);
        request.putString(RuntimeKeys.SESSION_ID, spec.sessionId);
        request.putLong(RuntimeKeys.GENERATION, spec.generation);
        request.putString(RuntimeKeys.PROCESS_NAME, targetProcess);
        request.putString(RuntimeKeys.CALLER_PACKAGE_NAME, spec.packageName);
        request.putInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, spec.virtualUserId);
        request.putInt(RuntimeKeys.CALLER_VIRTUAL_UID, spec.virtualUid);
        request.putString(RuntimeKeys.CALLER_SESSION_ID, spec.sessionId);
        request.putLong(RuntimeKeys.CALLER_GENERATION, spec.generation);
    }

    static void requireSuccess(Bundle result, String operation) {
        if (result == null) throw new IllegalStateException("BROKER_NULL_RESULT:" + operation);
        if (!"FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) return;
        String type = result.getString(RuntimeKeys.ERROR_TYPE, "BROKER_FAILURE");
        String message = result.getString(RuntimeKeys.ERROR_MESSAGE, "");
        if (SecurityException.class.getName().equals(type)
                || type.endsWith("SecurityException")) {
            throw new SecurityException(message.isEmpty() ? type : message);
        }
        throw new IllegalStateException(type + (message.isEmpty() ? "" : ":" + message));
    }
}
