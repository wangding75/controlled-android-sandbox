package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;

import java.util.EnumSet;
import java.util.Set;

public final class RuntimeOwnershipGraphSelfTest {
    private RuntimeOwnershipGraphSelfTest() { }

    public static void main(String[] args) {
        requireCatalog();
        requireDeathPreservesDurable();
        requireSweepAppliesHooksWithoutClearAll();
        requireStopRevokesEphemeralAndSessionGrantsButKeepsDurableSenders();
        requireStaleGenerationFence();
        System.out.println("PASS runtime ownership graph death/generation contract self-test");
    }

    private static void requireCatalog() {
        Set<RuntimeOwnershipGraph.Kind> kinds = RuntimeOwnershipGraph.requiredKinds();
        EnumSet<RuntimeOwnershipGraph.Kind> expected = EnumSet.of(
                RuntimeOwnershipGraph.Kind.GUEST_SESSION,
                RuntimeOwnershipGraph.Kind.PROCESS_SLOT,
                RuntimeOwnershipGraph.Kind.GENERATION,
                RuntimeOwnershipGraph.Kind.ACTIVITY,
                RuntimeOwnershipGraph.Kind.SERVICE,
                RuntimeOwnershipGraph.Kind.RECEIVER,
                RuntimeOwnershipGraph.Kind.PROVIDER_LEASE,
                RuntimeOwnershipGraph.Kind.PROVIDER_GRANT,
                RuntimeOwnershipGraph.Kind.PENDING_INTENT_SENDER,
                RuntimeOwnershipGraph.Kind.SYSTEM_SERVICE_STATE,
                RuntimeOwnershipGraph.Kind.SYSTEM_SERVICE_CALLBACK,
                RuntimeOwnershipGraph.Kind.NATIVE_CAPABILITY,
                RuntimeOwnershipGraph.Kind.BINDER_DEATH_RECIPIENT,
                RuntimeOwnershipGraph.Kind.JOB_REGISTRATION,
                RuntimeOwnershipGraph.Kind.ALARM_REGISTRATION,
                RuntimeOwnershipGraph.Kind.NOTIFICATION_REGISTRATION,
                RuntimeOwnershipGraph.Kind.ISOLATED_PEER_LEASE);
        check(kinds.equals(expected), "ownership catalog must list every required kind");
        for (RuntimeOwnershipGraph.Kind kind : expected) {
            check(kind.durability != null && kind.death != null && kind.restart != null
                            && kind.generation != null,
                    kind + " must define owner contract fields");
        }
    }

    private static void requireDeathPreservesDurable() {
        RuntimeOwnershipGraph.Kind[] durable = {
                RuntimeOwnershipGraph.Kind.PENDING_INTENT_SENDER,
                RuntimeOwnershipGraph.Kind.PROVIDER_GRANT,
                RuntimeOwnershipGraph.Kind.SYSTEM_SERVICE_STATE,
                RuntimeOwnershipGraph.Kind.JOB_REGISTRATION,
                RuntimeOwnershipGraph.Kind.ALARM_REGISTRATION,
                RuntimeOwnershipGraph.Kind.NOTIFICATION_REGISTRATION
        };
        for (RuntimeOwnershipGraph.Kind kind : durable) {
            check(RuntimeOwnershipGraph.durablePreservedOnDeath(kind),
                    kind + " must survive guest process death");
        }
        check(!RuntimeOwnershipGraph.durablePreservedOnDeath(
                        RuntimeOwnershipGraph.Kind.PROVIDER_LEASE),
                "ephemeral Provider leases must not be treated as durable");
        check(!RuntimeOwnershipGraph.durablePreservedOnDeath(
                        RuntimeOwnershipGraph.Kind.NATIVE_CAPABILITY),
                "native capability must revoke on death");
        check(!RuntimeOwnershipGraph.durablePreservedOnDeath(
                        RuntimeOwnershipGraph.Kind.ISOLATED_PEER_LEASE),
                "isolated peer lease must revoke on death");
    }

    private static void requireSweepAppliesHooksWithoutClearAll() {
        RecordingHooks hooks = new RecordingHooks();
        RuntimeOwnershipSweep sweep = new RuntimeOwnershipSweep(hooks);
        GuestSession session = session(3L);
        RuntimeOwnershipGraph.SweepReport report = sweep.death(session, "BINDER_DIED");
        check(hooks.activity == 1 && hooks.service == 1 && hooks.receiver == 1,
                "component authorities must sweep on death");
        check(hooks.providerLease == 1, "ephemeral Provider lease must sweep on death");
        check(hooks.providerGrant == 0, "persistable grants must not revoke on death");
        check(hooks.systemCallback == 1, "generation-scoped system-service callback must close");
        check(hooks.nativeCap == 1, "native capability must revoke");
        check(hooks.isolatedPeer == 1, "isolated peer lease must revoke");
        check(hooks.binderDeath == 1, "binder death recipient must unlink");
        check(hooks.clearAll == 0, "clearAll is forbidden");
        check(report.preserved(RuntimeOwnershipGraph.Kind.PENDING_INTENT_SENDER),
                "Broker IIntentSender must be preserved");
        check(report.preserved(RuntimeOwnershipGraph.Kind.JOB_REGISTRATION),
                "Job registration must be preserved");
        check(report.preserved(RuntimeOwnershipGraph.Kind.ALARM_REGISTRATION),
                "Alarm registration must be preserved");
        check(report.preserved(RuntimeOwnershipGraph.Kind.NOTIFICATION_REGISTRATION),
                "Notification registration must be preserved");
        check(report.executed(RuntimeOwnershipGraph.Kind.ACTIVITY), "activity must execute");
        check(!report.executed(RuntimeOwnershipGraph.Kind.PENDING_INTENT_SENDER),
                "durable PI must not execute a drop");
    }

    private static void requireStopRevokesEphemeralAndSessionGrantsButKeepsDurableSenders() {
        RecordingHooks hooks = new RecordingHooks();
        RuntimeOwnershipSweep sweep = new RuntimeOwnershipSweep(hooks);
        RuntimeOwnershipGraph.SweepReport report = sweep.stop(session(4L), "SESSION_STOPPED");
        check(hooks.providerGrant == 1, "explicit stop may revoke session grants");
        check(report.preserved(RuntimeOwnershipGraph.Kind.PENDING_INTENT_SENDER),
                "explicit stop must not drop Broker-owned IIntentSender");
        check(report.preserved(RuntimeOwnershipGraph.Kind.SYSTEM_SERVICE_STATE),
                "durable SystemService state must not be dropped on stop");
    }

    private static void requireStaleGenerationFence() {
        check(RuntimeOwnershipGraph.actionFor(RuntimeOwnershipGraph.Kind.GENERATION,
                        RuntimeOwnershipGraph.Event.DEATH)
                        == RuntimeOwnershipGraph.DeathAction.FENCE,
                "dead generation must be fenced");
        check(RuntimeOwnershipGraph.actionFor(RuntimeOwnershipGraph.Kind.GENERATION,
                        RuntimeOwnershipGraph.Event.GENERATION_SWITCH)
                        == RuntimeOwnershipGraph.DeathAction.FENCE,
                "replaced generation must be fenced");
        RecordingHooks hooks = new RecordingHooks();
        RuntimeOwnershipSweep sweep = new RuntimeOwnershipSweep(hooks);
        sweep.generationSwitch(session(1L), "STALE_GENERATION");
        check(hooks.nativeCap == 1 && hooks.isolatedPeer == 1,
                "generation switch must revoke ephemeral capabilities");
        check(hooks.providerGrant == 0, "generation switch rebinds grants; it does not drop them");
    }

    private static GuestSession session(long generation) {
        return new GuestSession("session-own", "com.example.guest", 0, "com.example.guest",
                3, generation, SessionState.RECOVERING, 10L, "");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingHooks implements RuntimeOwnershipSweep.Hooks {
        int activity;
        int service;
        int receiver;
        int providerLease;
        int providerGrant;
        int systemCallback;
        int nativeCap;
        int binderDeath;
        int isolatedPeer;
        int clearAll;

        @Override public void sweepActivity(GuestSession session, RuntimeOwnershipGraph.Event event) {
            activity++;
        }

        @Override public void sweepService(GuestSession session, RuntimeOwnershipGraph.Event event) {
            service++;
        }

        @Override public void sweepReceiver(GuestSession session, RuntimeOwnershipGraph.Event event,
                                            String reason) {
            receiver++;
        }

        @Override public void sweepProviderLease(GuestSession session,
                                                 RuntimeOwnershipGraph.Event event) {
            providerLease++;
        }

        @Override public void revokeProviderGrant(GuestSession session) {
            providerGrant++;
        }

        @Override public void sweepSystemServiceCallback(GuestSession session) {
            systemCallback++;
        }

        @Override public void revokeNativeCapability(GuestSession session) {
            nativeCap++;
        }

        @Override public void sweepBinderDeathRecipient(GuestSession session) {
            binderDeath++;
        }

        @Override public void revokeIsolatedPeer(GuestSession session) {
            isolatedPeer++;
        }

        @Override public void clearAll() {
            clearAll++;
            throw new UnsupportedOperationException("CLEAR_ALL_FORBIDDEN");
        }
    }
}
