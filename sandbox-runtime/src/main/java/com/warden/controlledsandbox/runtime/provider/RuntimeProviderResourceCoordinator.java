package com.warden.controlledsandbox.runtime.provider;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.List;
import java.util.Objects;

/**
 * Broker-facing owner for Provider resource cleanup and best-effort Guest close delivery.
 *
 * <p>This keeps cross-registry cleanup, stale-generation exclusion and Guest descriptor/cursor
 * close calls out of the central Runtime Broker.</p>
 */
public final class RuntimeProviderResourceCoordinator {
    @FunctionalInterface public interface SessionLookup {
        GuestSession find(String sessionId, long generation);
    }
    @FunctionalInterface public interface PreparedRequestLookup {
        Bundle prepared(GuestSession session);
    }
    @FunctionalInterface public interface GuestInvoker {
        Bundle invoke(int processSlot, Bundle request) throws Exception;
    }

    private final ProviderLifecycleCoordinator lifecycle;
    private final SessionLookup sessions;
    private final PreparedRequestLookup preparedRequests;
    private final GuestInvoker guestInvoker;

    public RuntimeProviderResourceCoordinator(ProviderLifecycleCoordinator lifecycle,
                                              SessionLookup sessions,
                                              PreparedRequestLookup preparedRequests,
                                              GuestInvoker guestInvoker) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.preparedRequests = Objects.requireNonNull(preparedRequests, "preparedRequests");
        this.guestInvoker = Objects.requireNonNull(guestInvoker, "guestInvoker");
    }

    public void purgeExpired(long nowMs) {
        apply(lifecycle.purgeExpired(nowMs), "", -1L);
    }

    public void stopSession(GuestSession session) {
        apply(lifecycle.stopSession(session), session.sessionId(), session.generation());
    }

    public void disconnectSession(GuestSession session) {
        apply(lifecycle.disconnectSession(session), session.sessionId(), session.generation());
    }

    public void invalidateInstance(String packageName, int virtualUserId) {
        apply(lifecycle.invalidateInstance(packageName, virtualUserId), "", -1L);
    }

    public void recoverSession(GuestSession stale, GuestSession current) {
        ProviderLifecycleCoordinator.RecoveryResult recovery = lifecycle.recoverSession(stale, current);
        apply(recovery.staleResources(), stale.sessionId(), stale.generation());
    }

    public void closeCursorBestEffort(GuestSession target, String token) {
        Bundle request = closeRequest(target, ComponentOperations.PROVIDER_CURSOR_CANCEL,
                RuntimeKeys.CURSOR_TOKEN, token);
        invokeBestEffort(target, request);
    }

    public void closeFileBestEffort(GuestSession target, String token) {
        Bundle request = closeRequest(target, ComponentOperations.PROVIDER_FILE_CLOSE,
                RuntimeKeys.FILE_TOKEN, token);
        invokeBestEffort(target, request);
    }

    private void apply(ProviderLifecycleCoordinator.CleanupResult cleanup,
                       String unavailableSessionId, long unavailableGeneration) {
        if (cleanup == null) return;
        closeCursors(cleanup.cursors(), unavailableSessionId, unavailableGeneration);
        closeFiles(cleanup.files(), unavailableSessionId, unavailableGeneration);
    }

    private void closeCursors(List<BrokerCursorRuntime.Lease> leases,
                              String unavailableSessionId, long unavailableGeneration) {
        for (BrokerCursorRuntime.Lease lease : leases) {
            if (sameUnavailable(lease.targetSessionId(), lease.targetGeneration(),
                    unavailableSessionId, unavailableGeneration)) continue;
            GuestSession target = liveTarget(lease.targetSessionId(), lease.targetGeneration());
            if (target == null) continue;
            Bundle request = closeRequest(target, ComponentOperations.PROVIDER_CURSOR_CANCEL,
                    RuntimeKeys.CURSOR_TOKEN, lease.token());
            invokeBestEffort(target, request);
        }
    }

    private void closeFiles(List<BrokerFileRuntime.Lease> leases,
                            String unavailableSessionId, long unavailableGeneration) {
        for (BrokerFileRuntime.Lease lease : leases) {
            if (sameUnavailable(lease.targetSessionId(), lease.targetGeneration(),
                    unavailableSessionId, unavailableGeneration)) continue;
            GuestSession target = liveTarget(lease.targetSessionId(), lease.targetGeneration());
            if (target == null) continue;
            Bundle request = closeRequest(target, ComponentOperations.PROVIDER_FILE_CLOSE,
                    RuntimeKeys.FILE_TOKEN, lease.token());
            invokeBestEffort(target, request);
        }
    }

    private GuestSession liveTarget(String sessionId, long generation) {
        GuestSession target = sessions.find(sessionId, generation);
        if (target == null) return null;
        return target.state() == SessionState.READY || target.state() == SessionState.ACTIVE
                ? target : null;
    }

    private Bundle closeRequest(GuestSession target, String operation, String tokenKey, String token) {
        if (token == null || token.trim().isEmpty()) return null;
        Bundle prepared = preparedRequests.prepared(target);
        if (prepared == null) return null;
        Bundle request = new Bundle(prepared);
        request.putString(ComponentOperations.OPERATION, operation);
        request.putString(tokenKey, token);
        return request;
    }

    private void invokeBestEffort(GuestSession target, Bundle request) {
        if (request == null) return;
        try { guestInvoker.invoke(target.processSlot(), request); }
        catch (Throwable ignored) { }
    }

    private static boolean sameUnavailable(String sessionId, long generation,
                                           String unavailableSessionId, long unavailableGeneration) {
        return sessionId.equals(unavailableSessionId) && generation == unavailableGeneration;
    }
}
