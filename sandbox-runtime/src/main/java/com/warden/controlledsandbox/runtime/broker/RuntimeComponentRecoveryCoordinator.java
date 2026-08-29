package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;

import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.component.activity.BrokerActivityRuntime;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.provider.RuntimeProviderResourceCoordinator;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coordinates cross-component recovery and fail-closed cleanup for a replacing Guest generation. */
final class RuntimeComponentRecoveryCoordinator {
    private final SessionRegistry sessions;
    private final Clock clock;
    private final BrokerActivityRuntime activities;
    private final RuntimeServiceCoordinator services;
    private final RuntimeReceiverCoordinator receivers;
    private final RuntimeProviderResourceCoordinator providers;
    private final RuntimeSystemServiceCoordinator systemServices;
    private final ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sandbox-component-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> pendingServiceRecoveries =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    RuntimeComponentRecoveryCoordinator(SessionRegistry sessions, Clock clock,
                                        BrokerActivityRuntime activities,
                                        RuntimeServiceCoordinator services,
                                        RuntimeReceiverCoordinator receivers,
                                        RuntimeProviderResourceCoordinator providers,
                                        RuntimeSystemServiceCoordinator systemServices) {
        if (sessions == null || clock == null || activities == null || services == null
                || receivers == null || providers == null || systemServices == null) {
            throw new IllegalArgumentException("Recovery coordinator dependencies are required");
        }
        this.sessions = sessions;
        this.clock = clock;
        this.activities = activities;
        this.services = services;
        this.receivers = receivers;
        this.providers = providers;
        this.systemServices = systemServices;
    }

    void recover(GuestSession stale, GuestSession current, Bundle currentSpec) throws Exception {
        try {
            activities.recreate(stale, current);
            receivers.recoverSession(stale, current);
            providers.recoverSession(stale, current);
            systemServices.stop(stale);
            scheduleServiceRecovery(stale, current, currentSpec);
        } catch (Throwable error) {
            try {
                cleanup(stale, current);
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("COMPONENT_RECOVERY_FAILED", error);
        }
    }

    /**
     * Service restart invokes Guest component callbacks and can run arbitrary application code.
     * Keep Activity/task recovery on the launch edge, but move sticky/redelivery Service work to
     * a generation-fenced daemon so a dead daemon cannot add its recovery latency to a new launch.
     */
    private void scheduleServiceRecovery(GuestSession stale, GuestSession current,
                                         Bundle currentSpec) {
        if (closed) return;
        String key = recoveryKey(current);
        if (!pendingServiceRecoveries.add(key)) return;
        Bundle spec = currentSpec == null ? new Bundle() : new Bundle(currentSpec);
        try {
            recoveryExecutor.execute(() -> recoverServicesAsync(stale, current, spec, key));
        } catch (RuntimeException rejected) {
            pendingServiceRecoveries.remove(key);
            RuntimeEventLog.event("GUEST_COMPONENT_RECOVERY_ASYNC_REJECTED",
                    event(current, "REJECTED", 0, rejected));
        }
    }

    private void recoverServicesAsync(GuestSession stale, GuestSession current,
                                      Bundle currentSpec, String key) {
        try {
            if (!ownsGeneration(current)) return;
            int recovered = services.recoverSession(stale, current, currentSpec).size();
            if (ownsGeneration(current)) {
                RuntimeEventLog.event("GUEST_COMPONENT_RECOVERY_ASYNC_COMPLETED",
                        event(current, "COMPLETED", recovered, null));
            } else {
                services.invalidate(stale);
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            // Remove only stale-generation records. A user may have started a new Service while
            // this best-effort recovery was running; invalidating the current generation would
            // incorrectly erase that live state and turn a recovery race into data loss.
            services.invalidate(stale);
            RuntimeEventLog.event("GUEST_COMPONENT_RECOVERY_ASYNC_FAILED",
                    event(current, "FAILED", 0, error));
        } finally {
            pendingServiceRecoveries.remove(key);
        }
    }

    private boolean ownsGeneration(GuestSession current) {
        GuestSession observed = sessions.get(current.packageName(), current.virtualUserId(),
                current.processName());
        return observed != null && observed.generation() == current.generation()
                && observed.state() != SessionState.STOPPED
                && observed.state() != SessionState.FAILED;
    }

    private static String recoveryKey(GuestSession current) {
        return current.packageName() + ":" + current.virtualUserId() + ":"
                + current.processName() + ":g" + current.generation();
    }

    private static Bundle event(GuestSession session, String status, int recovered,
                                Throwable error) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, status);
        out.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        out.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        out.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        out.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        out.putLong(RuntimeKeys.GENERATION, session.generation());
        out.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        out.putInt("recoveredServices", recovered);
        if (error != null) {
            out.putString(RuntimeKeys.ERROR_TYPE, error.getClass().getName());
            out.putString(RuntimeKeys.ERROR_MESSAGE, String.valueOf(error.getMessage()));
        }
        return out;
    }

    void close() {
        closed = true;
        recoveryExecutor.shutdownNow();
        pendingServiceRecoveries.clear();
    }

    private void cleanup(GuestSession stale, GuestSession current) {
        activities.invalidate(stale);
        activities.invalidate(current);
        services.invalidate(stale);
        services.invalidate(current);
        receivers.stopSession(stale, "ORDERED_RECEIVER_RECOVERY_FAILED");
        receivers.stopSession(current, "ORDERED_RECEIVER_RECOVERY_FAILED");
        providers.stopSession(stale);
        providers.stopSession(current);
        systemServices.stop(stale);
        systemServices.stop(current);
        GuestSession failed = sessions.get(current.packageName(), current.virtualUserId(), current.processName());
        if (failed != null && failed.state().canTransitionTo(SessionState.FAILED)) {
            sessions.transition(failed.packageName(), failed.virtualUserId(), failed.processName(),
                    failed.generation(), SessionState.FAILED, clock.nowMillis(), "COMPONENT_RECOVERY_FAILED");
        }
    }
}
