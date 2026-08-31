package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import android.util.Log;

import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Generation-fenced process recovery that never changes the triggering launch result. */
final class GuestRecoveryPrewarmCoordinator implements AutoCloseable {
    private static final long DELAY_MILLIS = 1_500L;

    interface Preparer {
        Bundle prepare(Bundle request);
    }

    interface EventFactory {
        Bundle event(GuestSession session, String status);
    }

    private final BrokerStateStore brokerState;
    private final SessionRegistry sessions;
    private final Preparer preparer;
    private final EventFactory events;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sandbox-guest-recovery-prewarm");
                thread.setDaemon(true);
                return thread;
            });
    private final ConcurrentHashMap<String, Boolean> pending = new ConcurrentHashMap<>();

    GuestRecoveryPrewarmCoordinator(BrokerStateStore brokerState, SessionRegistry sessions,
                                    Preparer preparer, EventFactory events) {
        if (brokerState == null || sessions == null || preparer == null || events == null) {
            throw new IllegalArgumentException("recovery prewarm dependencies are required");
        }
        this.brokerState = brokerState;
        this.sessions = sessions;
        this.preparer = preparer;
        this.events = events;
    }

    void schedule(GuestSession affected, String reason) {
        if (affected == null || affected.state() != SessionState.RECOVERING) return;
        String key = RuntimeBrokerService.processKey(affected.packageName(),
                affected.virtualUserId(), affected.processName());
        if (pending.putIfAbsent(key, Boolean.TRUE) != null) return;
        Bundle cached = brokerState.prepared(key);
        if (cached == null) {
            pending.remove(key);
            return;
        }
        Bundle request = new Bundle(cached);
        request.putString(RuntimeKeys.PACKAGE_NAME, affected.packageName());
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, affected.virtualUserId());
        request.putString(RuntimeKeys.PROCESS_NAME, affected.processName());
        executor.schedule(() -> recover(affected, reason, key, request),
                DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void recover(GuestSession affected, String reason, String key, Bundle request) {
        try {
            GuestSession current = sessions.get(affected.packageName(), affected.virtualUserId(),
                    affected.processName());
            if (current == null || current.state() != SessionState.RECOVERING
                    || current.generation() != affected.generation()) {
                return;
            }
            Bundle prepared = preparer.prepare(request);
            GuestSession recovered = sessions.get(affected.packageName(), affected.virtualUserId(),
                    affected.processName());
            String prewarmStatus = prepared.getString(RuntimeKeys.STATUS, "");
            if ("FAILED".equals(prewarmStatus)) {
                // prepare() may report a structured Guest failure instead of throwing.  That
                // result still owns the recovery generation and must close its PREPARING lease;
                // otherwise the next explicit launch is permanently rejected as SESSION_BUSY.
                rollbackFailedPreparation(recovered == null ? current : recovered, key,
                        prepared.getString(RuntimeKeys.ERROR_TYPE, "GUEST_PREPARE_FAILED"));
            }
            Bundle event = events.event(recovered == null ? current : recovered,
                    prewarmStatus);
            event.putString("reason", reason == null ? "" : reason);
            event.putString("prewarmStatus", prewarmStatus);
            RuntimeEventLog.event("GUEST_RECOVERY_PREWARM_COMPLETED", event);
        } catch (Throwable error) {
            FatalErrorPolicy.rethrowIfFatal(error);
            Log.w("CS_GUEST_RECOVERY", "prewarm failed package=" + affected.packageName()
                    + " user=" + affected.virtualUserId()
                    + " process=" + affected.processName(), error);
            Bundle event = events.event(affected, affected.state().name());
            event.putString("reason", reason == null ? "" : reason);
            event.putString("prewarmStatus", "FAILED:" + error.getClass().getSimpleName());
            RuntimeEventLog.event("GUEST_RECOVERY_PREWARM_FAILED", event);
        } finally {
            pending.remove(key);
        }
    }

    private void rollbackFailedPreparation(GuestSession current, String key, String failure) {
        if (current == null || (current.state() != SessionState.ALLOCATED
                && current.state() != SessionState.PREPARING)) return;
        try {
            sessions.transition(current.packageName(), current.virtualUserId(), current.processName(),
                    current.generation(), SessionState.FAILED, System.currentTimeMillis(),
                    "PREWARM_ROLLBACK:" + failure);
        } finally {
            brokerState.removePrepared(key);
        }
        RuntimeEventLog.event("GUEST_RECOVERY_PREWARM_ROLLBACK", events.event(current, "FAILED"));
    }

    @Override public void close() {
        executor.shutdownNow();
        pending.clear();
    }
}
