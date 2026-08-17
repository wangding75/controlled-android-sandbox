package com.warden.controlledsandbox.runtime.broker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Catalog of Broker-owned runtime objects and the death / generation contract for each.
 *
 * <p>This is not a universal {@code clearAll()}. Durable objects stay registered across guest
 * process death. Ephemeral objects are swept. Stale generations are fenced. Coordinators remain
 * the implementation authority; the graph only records policy and the sweep order.
 */
public final class RuntimeOwnershipGraph {
    public enum Durability { DURABLE, EPHEMERAL }

    public enum DeathAction { SWEEP, REVOKE, PRESERVE, FENCE }

    public enum RestartAction { RESTORE, RECREATE, NONE }

    public enum GenerationAction { FENCE, REBIND, INVALIDATE }

    public enum Event { DEATH, STOP, GENERATION_SWITCH, RECOVERY_FAILED }

    public enum Kind {
        GUEST_SESSION(Durability.EPHEMERAL, DeathAction.FENCE, RestartAction.RECREATE,
                GenerationAction.FENCE),
        PROCESS_SLOT(Durability.EPHEMERAL, DeathAction.SWEEP, RestartAction.RECREATE,
                GenerationAction.INVALIDATE),
        GENERATION(Durability.EPHEMERAL, DeathAction.FENCE, RestartAction.RECREATE,
                GenerationAction.FENCE),
        ACTIVITY(Durability.EPHEMERAL, DeathAction.SWEEP, RestartAction.RECREATE,
                GenerationAction.REBIND),
        SERVICE(Durability.EPHEMERAL, DeathAction.SWEEP, RestartAction.RECREATE,
                GenerationAction.REBIND),
        RECEIVER(Durability.EPHEMERAL, DeathAction.SWEEP, RestartAction.RECREATE,
                GenerationAction.INVALIDATE),
        PROVIDER_LEASE(Durability.EPHEMERAL, DeathAction.SWEEP, RestartAction.NONE,
                GenerationAction.INVALIDATE),
        PROVIDER_GRANT(Durability.DURABLE, DeathAction.PRESERVE, RestartAction.RESTORE,
                GenerationAction.REBIND),
        PENDING_INTENT_SENDER(Durability.DURABLE, DeathAction.PRESERVE, RestartAction.RESTORE,
                GenerationAction.FENCE),
        SYSTEM_SERVICE_STATE(Durability.DURABLE, DeathAction.PRESERVE, RestartAction.RESTORE,
                GenerationAction.FENCE),
        SYSTEM_SERVICE_CALLBACK(Durability.EPHEMERAL, DeathAction.SWEEP, RestartAction.NONE,
                GenerationAction.INVALIDATE),
        NATIVE_CAPABILITY(Durability.EPHEMERAL, DeathAction.REVOKE, RestartAction.NONE,
                GenerationAction.INVALIDATE),
        BINDER_DEATH_RECIPIENT(Durability.EPHEMERAL, DeathAction.SWEEP, RestartAction.NONE,
                GenerationAction.INVALIDATE),
        JOB_REGISTRATION(Durability.DURABLE, DeathAction.PRESERVE, RestartAction.RESTORE,
                GenerationAction.FENCE),
        ALARM_REGISTRATION(Durability.DURABLE, DeathAction.PRESERVE, RestartAction.RESTORE,
                GenerationAction.FENCE),
        NOTIFICATION_REGISTRATION(Durability.DURABLE, DeathAction.PRESERVE, RestartAction.RESTORE,
                GenerationAction.FENCE),
        ISOLATED_PEER_LEASE(Durability.EPHEMERAL, DeathAction.REVOKE, RestartAction.NONE,
                GenerationAction.INVALIDATE);

        public final Durability durability;
        public final DeathAction death;
        public final RestartAction restart;
        public final GenerationAction generation;

        Kind(Durability durability, DeathAction death, RestartAction restart,
             GenerationAction generation) {
            this.durability = durability;
            this.death = death;
            this.restart = restart;
            this.generation = generation;
        }
    }

    public static final class PlannedAction {
        public final Kind kind;
        public final DeathAction action;
        public final Event event;

        PlannedAction(Kind kind, DeathAction action, Event event) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.action = Objects.requireNonNull(action, "action");
            this.event = Objects.requireNonNull(event, "event");
        }

        public boolean preserves() {
            return action == DeathAction.PRESERVE;
        }
    }

    public static final class SweepReport {
        public final Event event;
        public final String reason;
        public final List<PlannedAction> preserved;
        public final List<PlannedAction> executed;

        SweepReport(Event event, String reason, List<PlannedAction> preserved,
                    List<PlannedAction> executed) {
            this.event = event;
            this.reason = reason == null ? "" : reason;
            this.preserved = Collections.unmodifiableList(new ArrayList<>(preserved));
            this.executed = Collections.unmodifiableList(new ArrayList<>(executed));
        }

        public boolean preserved(Kind kind) {
            for (PlannedAction action : preserved) {
                if (action.kind == kind) return true;
            }
            return false;
        }

        public boolean executed(Kind kind) {
            for (PlannedAction action : executed) {
                if (action.kind == kind) return true;
            }
            return false;
        }
    }

    private RuntimeOwnershipGraph() { }

    public static Set<Kind> requiredKinds() {
        return EnumSet.allOf(Kind.class);
    }

    public static List<PlannedAction> plan(Event event) {
        Objects.requireNonNull(event, "event");
        List<PlannedAction> planned = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            planned.add(new PlannedAction(kind, actionFor(kind, event), event));
        }
        return Collections.unmodifiableList(planned);
    }

    public static DeathAction actionFor(Kind kind, Event event) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(event, "event");
        if (event == Event.DEATH || event == Event.GENERATION_SWITCH) {
            return kind.death;
        }
        if (event == Event.STOP) {
            if (kind.durability == Durability.DURABLE
                    && (kind == Kind.PENDING_INTENT_SENDER
                    || kind == Kind.JOB_REGISTRATION
                    || kind == Kind.ALARM_REGISTRATION
                    || kind == Kind.NOTIFICATION_REGISTRATION
                    || kind == Kind.SYSTEM_SERVICE_STATE)) {
                return DeathAction.PRESERVE;
            }
            if (kind.durability == Durability.DURABLE && kind == Kind.PROVIDER_GRANT) {
                return DeathAction.REVOKE;
            }
            return kind.death == DeathAction.PRESERVE ? DeathAction.SWEEP : kind.death;
        }
        if (kind.durability == Durability.DURABLE) return DeathAction.PRESERVE;
        return DeathAction.SWEEP;
    }

    public static boolean durablePreservedOnDeath(Kind kind) {
        return kind.durability == Durability.DURABLE
                && actionFor(kind, Event.DEATH) == DeathAction.PRESERVE;
    }
}
