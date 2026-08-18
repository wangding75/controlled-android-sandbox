package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;

import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRevisionPolicy;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.io.File;
import java.util.ArrayList;

/**
 * Owns the Guest process lease transaction.
 *
 * <p>Preparation, generation recovery and stop are one lifecycle state machine.  Keeping that
 * state machine out of {@link RuntimeBrokerService} makes it possible to reason about the
 * bindApplication/LoadedApk boundary independently from Activity, Service and Provider routing.
 * The Broker remains the authority; this class only owns the process-lifecycle projection.</p>
 */
final class RuntimeGuestLifecycleCoordinator {
    private static final long STOP_TIMEOUT_MILLIS = 10_000L;

    private final RuntimeBrokerService owner;

    RuntimeGuestLifecycleCoordinator(RuntimeBrokerService owner) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }

    Bundle prepareGuest(Bundle request) {
        try {
            Bundle input = request == null ? new Bundle() : new Bundle(request);
            owner.validateInput(input);
            String packageName = input.getString(RuntimeKeys.PACKAGE_NAME, "");
            int userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            String processName = RuntimeBrokerService.processName(input, packageName);
            owner.validateDeclaredProcess(packageName, userId, processName);
            String packageRevision = RuntimeBrokerService.required(input,
                    RuntimeKeys.PACKAGE_REVISION);
            input.putString(RuntimeKeys.PROCESS_NAME, processName);
            stopMismatchedRevisionSessions(packageName, userId, packageRevision);
            owner.receiverCoordinator.indexPackage(input);
            padOrdinarySlots(packageName, userId, packageRevision, processName,
                    input.getInt(RuntimeKeys.SLOT_PAD_COUNT, 0),
                    input.getInt(RuntimeKeys.SLOT_TARGET, -1));
            GuestSession session = owner.sessions.allocate(
                    packageName, userId, processName, packageRevision, owner.now());
            GuestSession staleRecovery = null;
            String key = RuntimeBrokerService.processKey(packageName, userId, processName);
            if (session.state() == SessionState.READY || session.state() == SessionState.ACTIVE) {
                Bundle cached = owner.brokerState.prepared(key);
                if (cached == null) throw new IllegalStateException("PREPARED_SPEC_MISSING");
                if (!session.packageRevision().equals(
                        cached.getString(RuntimeKeys.PACKAGE_REVISION, ""))) {
                    throw new IllegalStateException("PREPARED_SPEC_REVISION_MISMATCH");
                }
                // A persisted READY/ACTIVE session is only a broker-side lease.  After a
                // process death Android may recreate the declared Guest service with an empty
                // GuestRuntimeEnvironment while the old session record and prepared spec still
                // exist.  Do not return the cached spec until the newly bound Binder proves that
                // bindApplication/LoadedApk/Application bootstrap is actually READY.
                Bundle runtimeStatus = null;
                Throwable statusFailure = null;
                try {
                    runtimeStatus = owner.callGuest(session.processSlot(), guest ->
                            RuntimeBrokerService.guestOperation(guest,
                                    RuntimeOperationRequest.GUEST_RUNTIME_STATUS, new Bundle()));
                } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                    statusFailure = error;
                }
                if (runtimeStatus != null
                        && "READY".equals(runtimeStatus.getString(RuntimeKeys.STATUS, ""))) {
                    owner.receiverCoordinator.bindSession(session);
                    Bundle out = new Bundle(cached);
                    out.putString(RuntimeKeys.STATUS, cached.getBoolean("frameworkDegraded", false)
                            ? "ALREADY_PREPARED_DEGRADED" : "ALREADY_PREPARED");
                    return out;
                }
                GuestSession observed = owner.sessions.get(packageName, userId, processName);
                if (observed != null && (observed.state() == SessionState.READY
                        || observed.state() == SessionState.ACTIVE)) {
                    String reason = statusFailure == null
                            ? "GUEST_RUNTIME_STATUS_" + (runtimeStatus == null
                                    ? "MISSING" : runtimeStatus.getString(RuntimeKeys.STATUS, "UNKNOWN"))
                            : "GUEST_RUNTIME_STATUS_FAILED:" + statusFailure.getClass().getSimpleName();
                    GuestSession died = owner.sessions.markProcessDied(packageName, userId,
                            processName, observed.generation(), owner.now(), reason);
                    // A stale Binder can remain technically reachable while the process has
                    // already lost its Guest runtime. Converge every component authority here,
                    // not only the SessionRegistry, so Service recovery sees RECOVERING records
                    // and no old Provider/Receiver/Activity capability survives the rebind.
                    owner.activityRuntime.processDisconnected(died);
                    owner.serviceCoordinator.disconnectSession(died);
                    owner.receiverCoordinator.disconnectSession(died,
                            "GUEST_RUNTIME_STATUS_NOT_READY:" + reason);
                    owner.providerResources.disconnectSession(died);
                }
                session = owner.sessions.get(packageName, userId, processName);
            }
            if (session.state() == SessionState.RECOVERING) {
                staleRecovery = session;
                session = owner.sessions.beginRecovery(packageName, userId, processName,
                        session.generation(), owner.now());
            } else if (session.state() == SessionState.ALLOCATED) {
                session = owner.sessions.transition(packageName, userId, processName,
                        session.generation(), SessionState.PREPARING, owner.now(), "");
            } else {
                throw new IllegalStateException("SESSION_BUSY:" + session.state());
            }
            Bundle spec = makeSpec(input, session);
            owner.systemServiceCoordinator.attach(session, spec);
            // Publish only the exact PREPARING generation so Application.onCreate() can use
            // standard Context APIs without recursively trying to prepare the same process.
            owner.brokerState.putPrepared(key, new Bundle(spec));
            Bundle guestResult;
            try {
                guestResult = owner.callGuest(session.processSlot(), guest ->
                        RuntimeBrokerService.guestOperation(guest,
                                RuntimeOperationRequest.PREPARE_GUEST, spec));
            } catch (Throwable error) {
                try {
                    owner.brokerState.removePrepared(key);
                    if (staleRecovery != null) {
                        owner.activityRuntime.invalidate(staleRecovery);
                        owner.serviceCoordinator.invalidate(staleRecovery);
                        owner.receiverCoordinator.stopSession(staleRecovery,
                                "ORDERED_RECEIVER_RECOVERY_FAILED");
                        owner.providerResources.stopSession(staleRecovery);
                    }
                    owner.systemServiceCoordinator.stop(session);
                } finally {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                throw error;
            }
            String guestStatus = guestResult.getString(RuntimeKeys.STATUS, "FAILED");
            boolean degraded = "DEGRADED".equals(guestStatus)
                    || "ALREADY_DEGRADED".equals(guestStatus);
            if (!"READY".equals(guestStatus) && !"ALREADY_READY".equals(guestStatus) && !degraded) {
                owner.sessions.transition(packageName, userId, processName, session.generation(),
                        SessionState.FAILED,
                        owner.now(), guestResult.getString(RuntimeKeys.ERROR_TYPE,
                                "GUEST_PREPARE_FAILED"));
                if (staleRecovery != null) {
                    owner.activityRuntime.invalidate(staleRecovery);
                    owner.serviceCoordinator.invalidate(staleRecovery);
                    owner.receiverCoordinator.stopSession(staleRecovery,
                            "ORDERED_RECEIVER_RECOVERY_FAILED");
                    owner.providerResources.stopSession(staleRecovery);
                }
                owner.systemServiceCoordinator.stop(session);
                owner.brokerState.removePrepared(key);
                return guestResult;
            }
            if (staleRecovery != null) {
                owner.componentRecoveryCoordinator.recover(staleRecovery, session, spec);
            }
            GuestSession ready = owner.sessions.transition(packageName, userId, processName,
                    session.generation(), SessionState.READY, owner.now(), "");
            Bundle cachedSpec = new Bundle(spec);
            cachedSpec.putBoolean("frameworkDegraded", degraded);
            owner.brokerState.putPrepared(key, cachedSpec);
            owner.receiverCoordinator.bindSession(ready);
            Bundle out = new Bundle(spec);
            out.putAll(guestResult);
            int guestPid = guestResult.getInt("pid", guestResult.getInt(RuntimeKeys.PLATFORM_PID, 0));
            if (guestPid > 0) out.putInt(RuntimeKeys.PLATFORM_PID, guestPid);
            out.putString(RuntimeKeys.STATUS, degraded ? "PREPARED_DEGRADED" : "PREPARED");
            out.putString("sessionState", ready.state().name());
            return out;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return RuntimeBrokerService.failure(error);
        }
    }

    void stopGuest(String packageName, int userId) {
        java.util.List<GuestSession> active = new ArrayList<>(owner.sessions.getAll(packageName, userId));
        for (GuestSession session : active) stopSession(session);
        owner.isolatedProcessCoordinator.stopGuest(packageName, userId);
        owner.receiverCoordinator.invalidateInstance(packageName, userId,
                "ORDERED_RECEIVER_INSTANCE_STOPPED");
        owner.providerResources.invalidateInstance(packageName, userId);
        owner.crossAbiProviderRelay.invalidateTarget(packageName, userId);
        owner.activityRuntime.clearPackageInstance(userId, packageName);
    }

    Bundle makeSpec(Bundle input, GuestSession session) throws Exception {
        Bundle spec = new Bundle(input);
        spec.putInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
        spec.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        spec.putLong(RuntimeKeys.GENERATION, session.generation());
        spec.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        spec.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        spec.putString(RuntimeKeys.PACKAGE_REVISION, session.packageRevision());
        spec.putInt(RuntimeKeys.VIRTUAL_UID,
                owner.uidRegistry().uidFor(session.packageName(), session.virtualUserId()));
        File dataRoot = new File(owner.getFilesDir(), "instances/u" + session.virtualUserId()
                + "/" + RuntimeBrokerService.safe(session.packageName()));
        if (!dataRoot.isDirectory() && !dataRoot.mkdirs() && !dataRoot.isDirectory()) {
            throw new IllegalStateException("Cannot create Guest instance root");
        }
        spec.putString(RuntimeKeys.DATA_ROOT, dataRoot.getCanonicalPath());
        if (input.getBoolean(RuntimeKeys.ISOLATED_PROCESS, false)) {
            spec.putBinder(RuntimeKeys.RUNTIME_STORAGE_BINDER,
                    owner.storageBinder.asBinder());
            spec.putBundle(RuntimeKeys.ISOLATED_FRAMEWORK_SERVICE_RELAYS,
                    FrameworkServiceRelay.capture());
            return owner.isolatedShares.prepare(spec, session);
        }
        return spec;
    }

    private void stopMismatchedRevisionSessions(String packageName, int userId,
                                                String requestedRevision) {
        java.util.List<GuestSession> existing = owner.sessions.getAll(packageName, userId);
        for (GuestSession session : SessionRevisionPolicy.mismatchedLiveSessions(
                existing, requestedRevision)) {
            stopSession(session);
        }
        owner.activityRuntime.clearMismatchedRevision(userId, packageName, requestedRevision);
    }

    // Do not hold the Broker monitor while waiting for Guest ActivityThread teardown.  Activity
    // onDestroy/unregisterReceiver callbacks synchronously re-enter this Binder; holding the
    // monitor here turns a normal stop into a callback deadlock and then an artificial timeout.
    private void stopSession(GuestSession original) {
        GuestSession session = original;
        Throwable stopFailure = null;
        try {
            if (session.state() != SessionState.STOPPING
                    && session.state() != SessionState.STOPPED
                    && session.state() != SessionState.FAILED) {
                session = owner.sessions.transition(session.packageName(), session.virtualUserId(),
                        session.processName(), session.generation(), SessionState.STOPPING,
                        owner.now(), "");
                final GuestSession stopping = session;
                owner.guestConnections.callWithTimeoutAndAwaitDisconnect(session.processSlot(),
                        guest -> {
                            guest.shutdown(stopping.sessionId(), stopping.generation());
                            Bundle out = new Bundle();
                            out.putString(RuntimeKeys.STATUS, "STOPPED");
                            return out;
                        }, STOP_TIMEOUT_MILLIS);
                owner.sessions.transition(session.packageName(), session.virtualUserId(),
                        session.processName(), session.generation(), SessionState.STOPPED,
                        owner.now(), "");
            }
        } catch (Throwable error) {
            stopFailure = error;
            try {
                GuestSession current = owner.sessions.get(original.packageName(),
                        original.virtualUserId(), original.processName());
                if (current != null && current.state() == SessionState.STOPPING
                        && current.state().canTransitionTo(SessionState.FAILED)) {
                    // A failed death barrier is not a successful stop.  Keep the slot out of
                    // service until Android reports the actual Binder/process death rather than
                    // releasing it while an old Guest can still execute teardown callbacks.
                    owner.sessions.transition(current.packageName(), current.virtualUserId(),
                            current.processName(), current.generation(), SessionState.FAILED,
                            owner.now(), String.valueOf(error.getMessage()));
                }
            } catch (Throwable transitionError) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(
                        transitionError);
            }
        } finally {
            owner.brokerState.removePrepared(RuntimeBrokerService.processKey(
                    original.packageName(), original.virtualUserId(), original.processName()));
            if (owner.ownershipSweep != null) {
                owner.ownershipSweep.stop(original, "ORDERED_RECEIVER_SESSION_STOPPED");
            } else {
                owner.receiverCoordinator.stopSession(original, "ORDERED_RECEIVER_SESSION_STOPPED");
                owner.activityRuntime.invalidate(original);
                owner.serviceCoordinator.stopSession(original);
                owner.providerResources.stopSession(original);
                owner.crossAbiProviderRelay.invalidateCaller(original.packageName(),
                        original.virtualUserId(), original.sessionId(), original.generation());
                if (owner.systemServiceCoordinator != null) {
                    owner.systemServiceCoordinator.stop(original);
                }
            }
            owner.releaseGuestConnection(original.processSlot());
        }
        if (stopFailure != null) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(stopFailure);
            if (stopFailure instanceof RuntimeException runtime) throw runtime;
            if (stopFailure instanceof Error fatal) throw fatal;
            throw new IllegalStateException("GUEST_STOP_FAILED", stopFailure);
        }
    }

    /**
     * Occupies ordinary slots in the SessionRegistry without starting Guest processes.
     * Used by RD high-slot transport probes so slot 31/32/62/63 can be exercised without
     * keeping 64 live APK-loaded processes.
     */
    private void padOrdinarySlots(String packageName, int userId, String packageRevision,
                                  String requestedProcess, int padCount, int slotTarget) {
        int capacity = com.warden.controlledsandbox.contract.ProcessSlotContract.ORDINARY_SLOT_COUNT;
        if (slotTarget >= 0) {
            if (!com.warden.controlledsandbox.contract.ProcessSlotContract.isOrdinarySlot(slotTarget)) {
                throw new IllegalArgumentException("SLOT_TARGET_OUT_OF_RANGE:" + slotTarget);
            }
            for (int slot = 0; slot < capacity; slot++) {
                if (slot == slotTarget) continue;
                String padProcess = packageName + ":__slot_pad_" + slot;
                if (padProcess.equals(requestedProcess)) {
                    throw new IllegalArgumentException("SLOT_PAD_COLLIDES_WITH_REQUEST");
                }
                owner.sessions.allocateExact(packageName, userId, padProcess, packageRevision,
                        slot, owner.now());
            }
            return;
        }
        if (padCount <= 0) return;
        if (padCount > capacity) {
            throw new IllegalArgumentException("SLOT_PAD_COUNT_OUT_OF_RANGE:" + padCount);
        }
        for (int index = 0; index < padCount; index++) {
            String padProcess = packageName + ":__slot_pad_" + index;
            if (padProcess.equals(requestedProcess)) {
                throw new IllegalArgumentException("SLOT_PAD_COLLIDES_WITH_REQUEST");
            }
            owner.sessions.allocateExact(packageName, userId, padProcess, packageRevision,
                    index, owner.now());
        }
    }
}
