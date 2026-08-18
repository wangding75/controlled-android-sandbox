package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.broker.CallerGuard;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
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
                    case RuntimeOperationRequest.SEND_PENDING_INTENT ->
                            sendPendingIntentInternal(request.sessionId(), request.generation(),
                                    request.payload());
                    case RuntimeOperationRequest.APPLY_ACTIVITY_HOST_DECISION ->
                            applyActivityHostDecision(request.payload());
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

    private Bundle sendPendingIntentInternal(String sessionId, long generation, Bundle request) {
        return GuestRuntimeEnvironment.sendPersistentPendingIntent(sessionId, generation, request);
    }

    private Bundle applyActivityHostDecision(Bundle request) {
        return com.warden.controlledsandbox.runtime.component.activity.StubActivityHostRegistry
                .apply(request);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        try {
            GuestRuntimeEnvironment.shutdownIfCurrent();
        } finally {
            try {
                super.onDestroy();
            } finally {
                // Each manifest GuestProcessService owns its entire :guestN process.  Android may
                // keep a stopped service process cached; terminate it after cleanup so a new
                // generation cannot create a second GuestClassLoader/native namespace in place.
                NativePolicy.setGuestProcessExitAllowed(true);
                Process.killProcess(Process.myPid());
            }
        }
    }
}
