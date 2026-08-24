package com.warden.controlledsandbox.runtime.guest;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import com.warden.controlledsandbox.contract.IIsolatedGuestProcess;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.IsolatedProcessRequest;
import com.warden.controlledsandbox.contract.IsolatedProcessResult;
import com.warden.controlledsandbox.contract.NativeExecutionProfile;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.runtime.broker.CallerGuard;
import com.warden.controlledsandbox.runtime.broker.IsolatedPeerAdmissionBinder;
import com.warden.controlledsandbox.runtime.broker.FrameworkServiceRelay;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/**
 * Capability-limited worker hosted by a manifest {@code isolatedProcess=true} Service.
 *
 * <p>Each subclass represents one immutable platform process slot. A worker accepts one active
 * package/user/component lease at a time and validates the outer typed identity before delegating
 * to the existing Guest runtime. It never exposes the ordinary Runtime Broker Binder to Guest code.
 * Real APK/data-path accessibility and SELinux behavior are intentionally device-gated.</p>
 */
public abstract class BaseIsolatedGuestProcessService extends Service {
    private String activeSessionId = "";
    private long activeGeneration;
    private String activeCapability = "";
    private String activeComponent = "";
    private String activePackage = "";

    protected abstract int isolatedSlot();

    private final IIsolatedGuestProcess.Stub binder = new IIsolatedGuestProcess.Stub() {
        @Override public IsolatedProcessResult prepare(IsolatedProcessRequest request) {
            CallerGuard.requireOwningApplication(BaseIsolatedGuestProcessService.this);
            try {
                validateRequest(request, "PREPARE_ISOLATED_SERVICE", false);
                if (Process.myUid() == getApplicationInfo().uid) {
                    throw new SecurityException("ISOLATED_PLATFORM_UID_NOT_ASSIGNED");
                }
                if (!activeSessionId.isEmpty()
                        && (!activeSessionId.equals(request.sessionId())
                        || activeGeneration != request.generation())) {
                    throw new IllegalStateException("ISOLATED_SLOT_ALREADY_LEASED");
                }
                Bundle payload = request.payload();
                NativePolicy.installHiddenApiBridge();
                FrameworkServiceRelay.install(payload.getBundle(
                        RuntimeKeys.ISOLATED_FRAMEWORK_SERVICE_RELAYS));
                bindOuterIdentity(payload, request);
                payload.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
                payload.putString(RuntimeKeys.ISOLATED_CAPABILITY_TOKEN, request.capabilityToken());
                IRuntimeBroker broker = IRuntimeBroker.Stub.asInterface(
                        payload.getBinder(RuntimeKeys.RUNTIME_BROKER_BINDER));
                if (broker == null) throw new SecurityException("ISOLATED_RUNTIME_BROKER_MISSING");
                registerIsolatedPeer(payload, request);
                // The Guest runtime still needs a Binder transport for framework component
                // routes. Expose only a generation/package-scoped facade; the raw Broker Binder
                // never enters Guest code and every typed request is revalidated here.
                payload.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER,
                        new ScopedRuntimeBroker(broker, request).asBinder());
                ParcelFileDescriptor sourceApk = payload.getParcelable(RuntimeKeys.ISOLATED_APK_FD);
                if (sourceApk == null) throw new IllegalStateException("ISOLATED_APK_CAPABILITY_MISSING");
                Bundle prepared = GuestRuntimeEnvironment.prepare(
                        BaseIsolatedGuestProcessService.this, new GuestPackageSpec(payload));
                String status = prepared.getString(RuntimeKeys.STATUS, "FAILED");
                if (!"READY".equals(status) && !"ALREADY_READY".equals(status)
                        && !"DEGRADED".equals(status) && !"ALREADY_DEGRADED".equals(status)) {
                    return new IsolatedProcessResult(false, "FAILED", request.sessionId(),
                            request.generation(), request.processSlot(), request.processName(),
                            request.componentClass(), Process.myPid(), Process.myUid(),
                            prepared.getString(RuntimeKeys.ERROR_TYPE, "ISOLATED_GUEST_PREPARE_FAILED"),
                            prepared.getString(RuntimeKeys.ERROR_MESSAGE, "Guest prepare failed"), prepared);
                }
                activeSessionId = request.sessionId();
                activeGeneration = request.generation();
                activeCapability = request.capabilityToken();
                activeComponent = request.componentClass();
                activePackage = request.packageName();
                prepared.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
                prepared.putInt(RuntimeKeys.ISOLATED_PLATFORM_PID, Process.myPid());
                prepared.putInt(RuntimeKeys.ISOLATED_PLATFORM_UID, Process.myUid());
                String profile = payload.getString(RuntimeKeys.NATIVE_EXECUTION_PROFILE, "");
                if (NativeExecutionProfile.isHostile(profile)) {
                    if (Process.myUid() == getApplicationInfo().uid) {
                        throw new SecurityException("HOSTILE_REQUIRES_ISOLATED_UID");
                    }
                    String seccomp = NativePolicy.installHostileSeccomp();
                    prepared.putString("hostileSeccomp", seccomp);
                    prepared.putString(RuntimeKeys.NATIVE_EXECUTION_PROFILE,
                            NativeExecutionProfile.ISOLATED_HOSTILE);
                }
                return IsolatedProcessResult.success(request, "ISOLATED_READY",
                        Process.myPid(), Process.myUid(), prepared);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                return failure(request, error);
            }
        }

        @Override public IsolatedProcessResult invoke(IsolatedProcessRequest request) {
            CallerGuard.requireOwningApplication(BaseIsolatedGuestProcessService.this);
            try {
                validateRequest(request, request.operation(), true);
                Bundle payload = request.payload();
                bindOuterIdentity(payload, request);
                payload.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
                payload.putString(RuntimeKeys.ISOLATED_CAPABILITY_TOKEN, request.capabilityToken());
                IRuntimeBroker broker = IRuntimeBroker.Stub.asInterface(
                        payload.getBinder(RuntimeKeys.RUNTIME_BROKER_BINDER));
                if (broker == null) throw new SecurityException("ISOLATED_RUNTIME_BROKER_MISSING");
                registerIsolatedPeer(payload, request);
                payload.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER,
                        new ScopedRuntimeBroker(broker, request).asBinder());
                Bundle result = GuestRuntimeEnvironment.require(
                        request.sessionId(), request.generation()).components.invoke(payload);
                result.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
                result.putInt(RuntimeKeys.ISOLATED_PLATFORM_PID, Process.myPid());
                result.putInt(RuntimeKeys.ISOLATED_PLATFORM_UID, Process.myUid());
                boolean successful = !"FAILED".equals(result.getString(RuntimeKeys.STATUS, ""));
                if (!successful) {
                    return new IsolatedProcessResult(false, "FAILED", request.sessionId(),
                            request.generation(), request.processSlot(), request.processName(),
                            request.componentClass(), Process.myPid(), Process.myUid(),
                            result.getString(RuntimeKeys.ERROR_TYPE, "ISOLATED_COMPONENT_FAILED"),
                            result.getString(RuntimeKeys.ERROR_MESSAGE, "Isolated component failed"), result);
                }
                return IsolatedProcessResult.success(request, "ISOLATED_OPERATION_COMPLETE",
                        Process.myPid(), Process.myUid(), result);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                return failure(request, error);
            }
        }

        @Override public IsolatedProcessResult status(IsolatedProcessRequest request) {
            CallerGuard.requireOwningApplication(BaseIsolatedGuestProcessService.this);
            try {
                validateRequest(request, "STATUS_ISOLATED_SERVICE", true);
                Bundle status = GuestRuntimeEnvironment.diagnosticStatus();
                status.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
                status.putInt(RuntimeKeys.ISOLATED_PLATFORM_PID, Process.myPid());
                status.putInt(RuntimeKeys.ISOLATED_PLATFORM_UID, Process.myUid());
                return IsolatedProcessResult.success(request, "ISOLATED_STATUS",
                        Process.myPid(), Process.myUid(), status);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                return failure(request, error);
            }
        }

        @Override public void shutdown(String sessionId, long generation, String capabilityToken) {
            CallerGuard.requireOwningApplication(BaseIsolatedGuestProcessService.this);
            requireActive(sessionId, generation, capabilityToken, activeComponent, activePackage);
            GuestRuntimeEnvironment.shutdown(sessionId, generation);
            clearLease();
            stopSelf();
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public boolean onUnbind(Intent intent) {
        android.util.Log.w("CS_ISOLATED_BIND", "worker onUnbind slot=" + isolatedSlot());
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        android.util.Log.w("CS_ISOLATED_BIND", "worker onDestroy slot=" + isolatedSlot()
                + " pid=" + Process.myPid());
        try {
            GuestRuntimeEnvironment.shutdownIfCurrent();
        } finally {
            try {
                clearLease();
                super.onDestroy();
            } finally {
                NativePolicy.setGuestProcessExitAllowed(true);
                Process.killProcess(Process.myPid());
            }
        }
    }

    private IsolatedProcessResult failure(IsolatedProcessRequest request, Throwable error) {
        if (request != null) {
            return IsolatedProcessResult.failure(request, error, Process.myPid(), Process.myUid());
        }
        return new IsolatedProcessResult(false, "FAILED", "malformed-request", 1L,
                isolatedSlot(), "malformed-isolated-process", "malformed-isolated-service",
                Process.myPid(), Process.myUid(),
                error == null ? "UNKNOWN" : error.getClass().getName(),
                error == null ? "Unknown isolated process failure" : String.valueOf(error.getMessage()),
                new Bundle());
    }

    private void validateRequest(IsolatedProcessRequest request, String operation,
                                 boolean requireActiveLease) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.processSlot() != isolatedSlot()) {
            throw new SecurityException("ISOLATED_SLOT_MISMATCH");
        }
        if (!operation.equals(request.operation())) {
            throw new SecurityException("ISOLATED_OPERATION_MISMATCH");
        }
        if (!"PREPARE_ISOLATED_SERVICE".equals(operation)
                && !"STATUS_ISOLATED_SERVICE".equals(operation)
                && !ComponentOperations.isServiceOperation(operation)) {
            throw new UnsupportedOperationException("ISOLATED_NON_SERVICE_OPERATION_REJECTED");
        }
        Bundle payload = request.payload();
        if (!request.packageName().equals(payload.getString(RuntimeKeys.PACKAGE_NAME, ""))
                || request.virtualUserId() != payload.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1)
                || !request.componentClass().equals(payload.getString(RuntimeKeys.COMPONENT_CLASS, ""))
                || !request.packageRevision().equals(payload.getString(RuntimeKeys.PACKAGE_REVISION, ""))) {
            throw new SecurityException("ISOLATED_OUTER_INNER_IDENTITY_MISMATCH");
        }
        if (requireActiveLease) {
            requireActive(request.sessionId(), request.generation(), request.capabilityToken(),
                    request.componentClass(), request.packageName());
        }
    }

    private void requireActive(String sessionId, long generation, String capabilityToken,
                               String component, String packageName) {
        if (!activeSessionId.equals(sessionId)) throw new SecurityException("ISOLATED_SESSION_MISMATCH");
        if (activeGeneration != generation) throw new SecurityException("ISOLATED_GENERATION_MISMATCH");
        if (!activeCapability.equals(capabilityToken)) throw new SecurityException("ISOLATED_CAPABILITY_MISMATCH");
        if (!activeComponent.equals(component)) throw new SecurityException("ISOLATED_COMPONENT_MISMATCH");
        if (!activePackage.equals(packageName)) throw new SecurityException("ISOLATED_PACKAGE_MISMATCH");
    }

    private static void bindOuterIdentity(Bundle payload, IsolatedProcessRequest request) {
        payload.putString(RuntimeKeys.SESSION_ID, request.sessionId());
        payload.putLong(RuntimeKeys.GENERATION, request.generation());
        payload.putInt(RuntimeKeys.PROCESS_SLOT, request.processSlot());
        payload.putInt(RuntimeKeys.VIRTUAL_USER_ID, request.virtualUserId());
        payload.putString(RuntimeKeys.PACKAGE_NAME, request.packageName());
        payload.putString(RuntimeKeys.PROCESS_NAME, request.processName());
        payload.putString(RuntimeKeys.COMPONENT_CLASS, request.componentClass());
        payload.putString(RuntimeKeys.PACKAGE_REVISION, request.packageRevision());
        if (!"PREPARE_ISOLATED_SERVICE".equals(request.operation())
                && !"STATUS_ISOLATED_SERVICE".equals(request.operation())) {
            payload.putString(ComponentOperations.OPERATION, request.operation());
        }
    }

    private static void registerIsolatedPeer(Bundle payload, IsolatedProcessRequest request)
            throws android.os.RemoteException {
        IBinder admission = payload == null ? null : payload.getBinder(RuntimeKeys.ISOLATED_PEER_ADMISSION);
        if (admission == null) throw new SecurityException("ISOLATED_PEER_ADMISSION_MISSING");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IsolatedPeerAdmissionBinder.DESCRIPTOR);
            data.writeString(request.sessionId());
            data.writeLong(request.generation());
            data.writeInt(request.processSlot());
            data.writeString(request.capabilityToken());
            if (!admission.transact(IsolatedPeerAdmissionBinder.TRANSACTION_REGISTER, data, reply, 0)) {
                throw new SecurityException("ISOLATED_PEER_ADMISSION_FAILED");
            }
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void clearLease() {
        activeSessionId = "";
        activeGeneration = 0L;
        activeCapability = "";
        activeComponent = "";
        activePackage = "";
    }

    private static final class ScopedRuntimeBroker extends IRuntimeBroker.Stub {
        private final IRuntimeBroker delegate;
        private final String sessionId;
        private final long generation;
        private final String packageName;
        private final int virtualUserId;

        ScopedRuntimeBroker(IRuntimeBroker delegate, IsolatedProcessRequest request) {
            this.delegate = delegate;
            this.sessionId = request.sessionId();
            this.generation = request.generation();
            this.packageName = request.packageName();
            this.virtualUserId = request.virtualUserId();
        }

        @Override public RuntimeOperationResult executeV2(RuntimeOperationRequest request)
                throws android.os.RemoteException {
            require(request == null ? null : request.sessionId(),
                    request == null ? 0 : request.generation(),
                    request == null ? null : request.packageName(),
                    request == null ? -1 : request.virtualUserId());
            return delegate.executeV2(request);
        }

        @Override public ActivityTaskResult activityTaskOperation(ActivityTaskRequest request)
                throws android.os.RemoteException {
            require(request == null ? null : request.sessionId(),
                    request == null ? 0 : request.generation(),
                    request == null ? null : request.packageName(),
                    request == null ? -1 : request.virtualUserId());
            return delegate.activityTaskOperation(request);
        }

        @Override public ActivityResultResult activityResultOperation(ActivityResultRequest request)
                throws android.os.RemoteException {
            require(request == null ? null : request.sessionId(),
                    request == null ? 0 : request.generation(),
                    request == null ? null : request.packageName(),
                    request == null ? -1 : request.virtualUserId());
            return delegate.activityResultOperation(request);
        }

        @Override public PackageServiceResult requestRuntimePermission(String sessionId,
                long generation, String permission, int requestCode)
                throws android.os.RemoteException {
            require(sessionId, generation, packageName, virtualUserId);
            return delegate.requestRuntimePermission(sessionId, generation, permission, requestCode);
        }

        @Override public PackageServiceResult reportRuntimePermissionResult(String sessionId,
                long generation, String permission, int requestCode, boolean hostGranted,
                String reason) throws android.os.RemoteException {
            require(sessionId, generation, packageName, virtualUserId);
            return delegate.reportRuntimePermissionResult(sessionId, generation, permission,
                    requestCode, hostGranted, reason);
        }

        @Override public RuntimeStatusResult runtimeStatusV2(RuntimeStatusRequest request)
                throws android.os.RemoteException {
            if (request == null) throw new SecurityException("ISOLATED_STATUS_REQUEST_MISSING");
            return delegate.runtimeStatusV2(request);
        }

        @Override public int virtualUidFor(String packageName, int virtualUserId)
                throws android.os.RemoteException {
            if (!ScopedRuntimeBroker.this.packageName.equals(packageName)
                    || ScopedRuntimeBroker.this.virtualUserId != virtualUserId) {
                throw new SecurityException("ISOLATED_UID_IDENTITY_MISMATCH");
            }
            return delegate.virtualUidFor(packageName, virtualUserId);
        }

        @Override public void stopGuest(String packageName, int virtualUserId)
                throws android.os.RemoteException {
            if (!ScopedRuntimeBroker.this.packageName.equals(packageName)
                    || ScopedRuntimeBroker.this.virtualUserId != virtualUserId) {
                throw new SecurityException("ISOLATED_STOP_IDENTITY_MISMATCH");
            }
            delegate.stopGuest(packageName, virtualUserId);
        }

        private void require(String requestSession, long requestGeneration,
                             String requestPackage, int requestUser) {
            if (!sessionId.equals(requestSession) || generation != requestGeneration
                    || !packageName.equals(requestPackage) || virtualUserId != requestUser) {
                throw new SecurityException("ISOLATED_RUNTIME_BROKER_IDENTITY_MISMATCH");
            }
        }
    }
}
