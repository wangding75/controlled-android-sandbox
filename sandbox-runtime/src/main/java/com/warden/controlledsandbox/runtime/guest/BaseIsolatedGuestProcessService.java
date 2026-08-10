package com.warden.controlledsandbox.runtime.guest;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import com.warden.controlledsandbox.contract.IIsolatedGuestProcess;
import com.warden.controlledsandbox.contract.IsolatedProcessRequest;
import com.warden.controlledsandbox.contract.IsolatedProcessResult;
import com.warden.controlledsandbox.runtime.broker.CallerGuard;
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
                bindOuterIdentity(payload, request);
                payload.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
                payload.putString(RuntimeKeys.ISOLATED_CAPABILITY_TOKEN, request.capabilityToken());
                // Do not expose the full Runtime Broker Binder inside the isolated Guest.
                payload.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER, null);
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
                payload.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER, null);
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
                Bundle status = GuestRuntimeEnvironment.status();
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

    @Override public void onDestroy() {
        try {
            GuestRuntimeEnvironment.shutdownIfCurrent();
        } finally {
            clearLease();
            super.onDestroy();
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

    private void clearLease() {
        activeSessionId = "";
        activeGeneration = 0L;
        activeCapability = "";
        activeComponent = "";
        activePackage = "";
    }
}
