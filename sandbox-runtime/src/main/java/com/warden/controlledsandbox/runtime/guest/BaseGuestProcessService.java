package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.broker.CallerGuard;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IGuestProcess;

public abstract class BaseGuestProcessService extends Service {
    private final IGuestProcess.Stub binder = new IGuestProcess.Stub() {
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
