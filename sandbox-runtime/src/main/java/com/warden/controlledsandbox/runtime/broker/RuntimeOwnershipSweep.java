package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.domain.session.GuestSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies {@link RuntimeOwnershipGraph} policy through existing coordinators.
 *
 * <p>Hooks are the real authorities. This class only decides which authorities run and
 * forbids a universal {@code clearAll()} path.
 */
final class RuntimeOwnershipSweep {
    interface Hooks {
        default void sweepActivity(GuestSession session, RuntimeOwnershipGraph.Event event) { }

        default void sweepService(GuestSession session, RuntimeOwnershipGraph.Event event) { }

        default void sweepReceiver(GuestSession session, RuntimeOwnershipGraph.Event event,
                                   String reason) { }

        default void sweepProviderLease(GuestSession session, RuntimeOwnershipGraph.Event event) { }

        default void revokeProviderGrant(GuestSession session) { }

        default void sweepSystemServiceCallback(GuestSession session) { }

        default void revokeNativeCapability(GuestSession session) { }

        default void sweepBinderDeathRecipient(GuestSession session) { }

        default void revokeIsolatedPeer(GuestSession session) { }

        default void clearAll() {
            throw new UnsupportedOperationException("CLEAR_ALL_FORBIDDEN");
        }
    }

    private final Hooks hooks;

    RuntimeOwnershipSweep(Hooks hooks) {
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    RuntimeOwnershipGraph.SweepReport death(GuestSession session, String reason) {
        return apply(RuntimeOwnershipGraph.Event.DEATH, session, reason);
    }

    RuntimeOwnershipGraph.SweepReport stop(GuestSession session, String reason) {
        return apply(RuntimeOwnershipGraph.Event.STOP, session, reason);
    }

    RuntimeOwnershipGraph.SweepReport generationSwitch(GuestSession session, String reason) {
        return apply(RuntimeOwnershipGraph.Event.GENERATION_SWITCH, session, reason);
    }

    private RuntimeOwnershipGraph.SweepReport apply(RuntimeOwnershipGraph.Event event,
                                                    GuestSession session, String reason) {
        if (session == null) throw new IllegalArgumentException("session is required");
        List<RuntimeOwnershipGraph.PlannedAction> preserved = new ArrayList<>();
        List<RuntimeOwnershipGraph.PlannedAction> executed = new ArrayList<>();
        for (RuntimeOwnershipGraph.PlannedAction planned : RuntimeOwnershipGraph.plan(event)) {
            if (planned.preserves()) {
                preserved.add(planned);
                continue;
            }
            dispatch(planned, session, reason);
            executed.add(planned);
        }
        return new RuntimeOwnershipGraph.SweepReport(event, reason, preserved, executed);
    }

    private void dispatch(RuntimeOwnershipGraph.PlannedAction planned, GuestSession session,
                          String reason) {
        switch (planned.kind) {
            case ACTIVITY -> hooks.sweepActivity(session, planned.event);
            case SERVICE -> hooks.sweepService(session, planned.event);
            case RECEIVER -> hooks.sweepReceiver(session, planned.event, reason);
            case PROVIDER_LEASE -> hooks.sweepProviderLease(session, planned.event);
            case PROVIDER_GRANT -> {
                if (planned.action == RuntimeOwnershipGraph.DeathAction.REVOKE) {
                    hooks.revokeProviderGrant(session);
                }
            }
            case SYSTEM_SERVICE_CALLBACK -> hooks.sweepSystemServiceCallback(session);
            case NATIVE_CAPABILITY -> hooks.revokeNativeCapability(session);
            case BINDER_DEATH_RECIPIENT -> hooks.sweepBinderDeathRecipient(session);
            case ISOLATED_PEER_LEASE -> hooks.revokeIsolatedPeer(session);
            case GUEST_SESSION, PROCESS_SLOT, GENERATION -> {
                // SessionRegistry / SlotPool remain the process-lease authority.
            }
            case PENDING_INTENT_SENDER, SYSTEM_SERVICE_STATE, JOB_REGISTRATION,
                    ALARM_REGISTRATION, NOTIFICATION_REGISTRATION -> {
                throw new IllegalStateException("DURABLE_KIND_MUST_PRESERVE:" + planned.kind);
            }
        }
    }
}
