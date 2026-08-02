package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.broker.CallerGuard;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IGuestProcess;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;

public abstract class BaseGuestProcessService extends Service {
    private final IGuestProcess.Stub binder = new IGuestProcess.Stub() {
        @Override public RuntimeOperationResult executeV2(RuntimeOperationRequest request) {
            CallerGuard.requireSameApplication();
            if (request == null) throw new IllegalArgumentException("request is required");
            try {
                Bundle result = switch (request.operation()) {
                    case RuntimeOperationRequest.PREPARE_GUEST -> prepareGuestInternal(request.payload());
                    case RuntimeOperationRequest.INVOKE_COMPONENT -> invokeComponentInternal(request.payload());
                    case RuntimeOperationRequest.GUEST_RUNTIME_STATUS -> runtimeStatusInternal();
                    default -> throw new IllegalArgumentException(
                            "unsupported guest operation: " + request.operation());
                };
                return RuntimeOperationTransport.fromLegacy(request, result);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                return RuntimeOperationTransport.failure(request, error);
            }
        }



        @Override public void shutdown(String sessionId, long generation) {
            CallerGuard.requireSameApplication();
            GuestRuntimeEnvironment.shutdown(sessionId, generation);
        }
    };
    private Bundle prepareGuestInternal(Bundle request) {
        return GuestRuntimeEnvironment.prepare(this, new GuestPackageSpec(request));
    }

    private Bundle invokeComponentInternal(Bundle request) {
        GuestPackageSpec spec = new GuestPackageSpec(request);
        return GuestRuntimeEnvironment.require(spec.sessionId, spec.generation)
                .components.invoke(request);
    }

    private Bundle runtimeStatusInternal() {
        return GuestRuntimeEnvironment.status();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
}
