package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
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
        return execute(RuntimeOperationRequest.INVOKE_COMPONENT, request);
    }

    void invokeComponentAsync(Bundle request, Consumer<Bundle> success,
                              Consumer<Throwable> failure) {
        mainThread.callBrokerAsync(
                () -> execute(RuntimeOperationRequest.INVOKE_COMPONENT, request), success, failure);
    }

    Bundle launchActivity(Bundle request) {
        return execute(RuntimeOperationRequest.LAUNCH_ACTIVITY, request);
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
        Bundle request = spec.toBundle();
        request.putString(RuntimeKeys.CALLER_PACKAGE_NAME, spec.packageName);
        request.putInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, spec.virtualUserId);
        request.putString(RuntimeKeys.CALLER_SESSION_ID, spec.sessionId);
        request.putLong(RuntimeKeys.CALLER_GENERATION, spec.generation);
        return request;
    }

    private Bundle execute(String operation, Bundle request) {
        try {
            if (broker == null) throw new IllegalStateException("RUNTIME_BROKER_CAPABILITY_INVALID");
            Bundle payload = request == null ? baseRequest() : new Bundle(request);
            fillIdentity(payload);
            Bundle result = mainThread.callBroker(() -> RuntimeOperationTransport.toLegacyBundle(
                    RuntimeOperationTransport.execute(broker, operation, payload)));
            requireSuccess(result, operation);
            return result;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
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
