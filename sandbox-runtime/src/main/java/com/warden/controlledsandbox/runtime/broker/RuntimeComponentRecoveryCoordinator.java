package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;

import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.component.activity.BrokerActivityRuntime;
import com.warden.controlledsandbox.runtime.provider.RuntimeProviderResourceCoordinator;

/** Coordinates cross-component recovery and fail-closed cleanup for a replacing Guest generation. */
final class RuntimeComponentRecoveryCoordinator {
    private final SessionRegistry sessions;
    private final Clock clock;
    private final BrokerActivityRuntime activities;
    private final RuntimeServiceCoordinator services;
    private final RuntimeReceiverCoordinator receivers;
    private final RuntimeProviderResourceCoordinator providers;
    private final RuntimeSystemServiceCoordinator systemServices;

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
            services.recoverSession(stale, current, currentSpec);
            receivers.recoverSession(stale, current);
            providers.recoverSession(stale, current);
            systemServices.stop(stale);
        } catch (Throwable error) {
            cleanup(stale, current);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("COMPONENT_RECOVERY_FAILED", error);
        }
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
