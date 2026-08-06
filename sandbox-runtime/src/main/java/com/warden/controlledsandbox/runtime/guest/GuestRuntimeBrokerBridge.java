package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;

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

    Bundle launchActivity(Bundle request) {
        return execute(RuntimeOperationRequest.LAUNCH_ACTIVITY, request);
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
        request.putInt(RuntimeKeys.PROTOCOL,
                com.warden.controlledsandbox.domain.protocol.RuntimeProtocol.CURRENT);
        request.putString(RuntimeKeys.PACKAGE_NAME, spec.packageName);
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, spec.virtualUserId);
        request.putString(RuntimeKeys.SESSION_ID, spec.sessionId);
        request.putLong(RuntimeKeys.GENERATION, spec.generation);
        request.putString(RuntimeKeys.PROCESS_NAME, spec.processName);
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
