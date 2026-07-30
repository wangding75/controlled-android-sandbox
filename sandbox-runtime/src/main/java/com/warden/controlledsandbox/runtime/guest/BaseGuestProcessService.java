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
                    case RuntimeOperationRequest.PREPARE_GUEST -> prepareGuest(request.payload());
                    case RuntimeOperationRequest.INVOKE_COMPONENT -> invokeComponent(request.payload());
                    case RuntimeOperationRequest.GUEST_RUNTIME_STATUS -> runtimeStatus();
                    default -> throw new IllegalArgumentException(
                            "unsupported guest operation: " + request.operation());
                };
                return RuntimeOperationTransport.fromLegacy(request, result);
            } catch (Throwable error) {
                return RuntimeOperationTransport.failure(request, error);
            }
        }
        @Override public Bundle prepareGuest(Bundle request) {
            CallerGuard.requireSameApplication();
            return GuestRuntimeEnvironment.prepare(BaseGuestProcessService.this, new GuestPackageSpec(request));
        }
        @Override public Bundle invokeComponent(Bundle request) {
            CallerGuard.requireSameApplication();
            GuestPackageSpec spec = new GuestPackageSpec(request);
            return GuestRuntimeEnvironment.require(spec.sessionId, spec.generation).components.invoke(request);
        }
        @Override public Bundle runtimeStatus() {
            CallerGuard.requireSameApplication();
            return GuestRuntimeEnvironment.status();
        }
        @Override public void shutdown(String sessionId, long generation) {
            CallerGuard.requireSameApplication();
            GuestRuntimeEnvironment.shutdown(sessionId, generation);
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
}
