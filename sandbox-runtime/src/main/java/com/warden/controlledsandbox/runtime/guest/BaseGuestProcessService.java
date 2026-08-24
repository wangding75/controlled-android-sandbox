package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.broker.CallerGuard;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import com.warden.controlledsandbox.contract.IGuestProcess;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;

public abstract class BaseGuestProcessService extends Service {
    /**
     * A live Guest process is the owner of a Broker session.  Keep that ownership visible to
     * Android's process manager with a reverse service lease; the raw Binder in GuestPackageSpec
     * is a capability transport, not an Android process-priority relationship.  This mirrors the
     * NBB/VA ProcessRecord owner edge without exposing the Broker root to Guest code.
     */
    private ServiceConnection brokerOwnerLease;
    private boolean brokerOwnerLeaseBound;

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
        acquireBrokerOwnerLease(new GuestPackageSpec(request));
        return GuestRuntimeEnvironment.prepare(this, new GuestPackageSpec(request));
    }

    private synchronized void acquireBrokerOwnerLease(GuestPackageSpec spec) {
        if (brokerOwnerLeaseBound) return;
        final String sessionId = spec.sessionId;
        final long generation = spec.generation;
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                android.util.Log.i("CS_PROCESS_OWNER", "BROKER_OWNER_LEASE_CONNECTED session="
                        + sessionId + " generation=" + generation + " pid=" + Process.myPid());
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                android.util.Log.w("CS_PROCESS_OWNER", "BROKER_OWNER_LEASE_DISCONNECTED session="
                        + sessionId + " generation=" + generation + " pid=" + Process.myPid());
            }

            @Override public void onBindingDied(ComponentName name) {
                android.util.Log.w("CS_PROCESS_OWNER", "BROKER_OWNER_LEASE_DIED session="
                        + sessionId + " generation=" + generation + " pid=" + Process.myPid());
            }

            @Override public void onNullBinding(ComponentName name) {
                android.util.Log.e("CS_PROCESS_OWNER", "BROKER_OWNER_LEASE_NULL session="
                        + sessionId + " generation=" + generation + " pid=" + Process.myPid());
            }
        };
        Intent intent = new Intent(this, RuntimeBrokerService.class);
        boolean accepted;
        try {
            accepted = bindService(intent, connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT
                            | Context.BIND_ABOVE_CLIENT);
        } catch (RuntimeException error) {
            android.util.Log.e("CS_PROCESS_OWNER", "BROKER_OWNER_LEASE_REJECTED session="
                    + sessionId + " generation=" + generation + " pid=" + Process.myPid(), error);
            throw new IllegalStateException("RUNTIME_BROKER_OWNER_LEASE_REJECTED", error);
        }
        if (!accepted) {
            android.util.Log.e("CS_PROCESS_OWNER", "BROKER_OWNER_LEASE_REJECTED session="
                    + sessionId + " generation=" + generation + " pid=" + Process.myPid());
            throw new IllegalStateException("RUNTIME_BROKER_OWNER_LEASE_REJECTED");
        }
        brokerOwnerLease = connection;
        brokerOwnerLeaseBound = true;
        android.util.Log.i("CS_PROCESS_OWNER", "BROKER_OWNER_LEASE_REQUESTED session="
                + sessionId + " generation=" + generation + " pid=" + Process.myPid()
                + " flags=BIND_AUTO_CREATE|BIND_IMPORTANT|BIND_ABOVE_CLIENT retry=false");
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

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        try {
            GuestRuntimeEnvironment.shutdownIfCurrent();
        } finally {
            try {
                releaseBrokerOwnerLease();
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

    private synchronized void releaseBrokerOwnerLease() {
        if (!brokerOwnerLeaseBound || brokerOwnerLease == null) return;
        ServiceConnection connection = brokerOwnerLease;
        brokerOwnerLease = null;
        brokerOwnerLeaseBound = false;
        try {
            unbindService(connection);
            android.util.Log.i("CS_PROCESS_OWNER", "BROKER_OWNER_LEASE_RELEASED pid="
                    + Process.myPid());
        } catch (RuntimeException error) {
            android.util.Log.w("CS_PROCESS_OWNER", "BROKER_OWNER_LEASE_RELEASE_FAILED pid="
                    + Process.myPid(), error);
        }
    }
}
