package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

/**
 * Generation- and owner-scoped lifecycle fence for a host Stub Activity window.
 *
 * <p>The Android framework owns the real DecorView. This class only records which virtual
 * Activity is allowed to issue a lifecycle/window operation; it never suppresses a framework
 * exception and it never changes task-switch semantics.</p>
 */
public final class StubActivityWindowOwnership {
    public enum Stage { UNBOUND, DETACHED, ATTACHED, DESTROYED }

    public record Owner(String packageName, int virtualUserId, String sessionId, long generation,
                        int processSlot, String activityToken, int taskId) {
        public Owner {
            requireText(packageName, "packageName");
            if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
            requireText(sessionId, "sessionId");
            if (generation < 1) throw new IllegalArgumentException("generation must be positive");
            if (processSlot < 0 || processSlot > 31) {
                throw new IllegalArgumentException("processSlot must be in 0..31");
            }
            requireText(activityToken, "activityToken");
            if (taskId < 1) throw new IllegalArgumentException("taskId must be positive");
        }

        public Owner withActivityToken(String nextActivityToken) {
            return new Owner(packageName, virtualUserId, sessionId, generation, processSlot,
                    nextActivityToken, taskId);
        }
    }

    /** Immutable callback lease. A lease from an earlier owner or generation is stale. */
    public record Lease(Owner owner, long epoch) {
        public Lease {
            Objects.requireNonNull(owner, "owner");
            if (epoch < 1) throw new IllegalArgumentException("epoch must be positive");
        }
    }

    private Owner owner;
    private long epoch;
    private Stage stage = Stage.UNBOUND;
    private String windowIdentity = "";

    public synchronized Lease bind(Owner nextOwner) {
        Objects.requireNonNull(nextOwner, "nextOwner");
        if (stage == Stage.DESTROYED) throw new IllegalStateException("WINDOW_OWNER_DESTROYED");
        if (!nextOwner.equals(owner)) {
            owner = nextOwner;
            epoch++;
            stage = Stage.DETACHED;
            windowIdentity = "";
        } else if (stage == Stage.UNBOUND) {
            epoch++;
            stage = Stage.DETACHED;
        }
        return new Lease(owner, epoch);
    }

    public synchronized Lease replace(Lease expected, Owner nextOwner) {
        requireCurrent(expected);
        Objects.requireNonNull(nextOwner, "nextOwner");
        if (nextOwner.equals(owner)) return expected;
        owner = nextOwner;
        epoch++;
        stage = Stage.DETACHED;
        windowIdentity = "";
        return new Lease(owner, epoch);
    }

    public synchronized boolean attach(Lease candidate, String nextWindowIdentity) {
        if (!accepts(candidate) || isBlank(nextWindowIdentity)) return false;
        stage = Stage.ATTACHED;
        windowIdentity = nextWindowIdentity;
        return true;
    }

    public synchronized boolean detach(Lease candidate, String detachedWindowIdentity) {
        if (!accepts(candidate) || !sameWindow(detachedWindowIdentity)) return false;
        stage = Stage.DETACHED;
        return true;
    }

    public synchronized boolean mayUpdate(Lease candidate, String candidateWindowIdentity) {
        return accepts(candidate) && stage == Stage.ATTACHED && sameWindow(candidateWindowIdentity);
    }

    public synchronized boolean accepts(Lease candidate) {
        return candidate != null && owner != null && epoch == candidate.epoch()
                && owner.equals(candidate.owner()) && stage != Stage.DESTROYED;
    }

    public synchronized void destroy(Lease candidate) {
        requireCurrent(candidate);
        stage = Stage.DESTROYED;
        windowIdentity = "";
    }

    public synchronized Lease currentLease() {
        return owner == null || stage == Stage.UNBOUND || stage == Stage.DESTROYED
                ? null : new Lease(owner, epoch);
    }

    public synchronized Owner currentOwner() { return owner; }
    public synchronized Stage stage() { return stage; }
    public synchronized long epoch() { return epoch; }

    private void requireCurrent(Lease candidate) {
        if (!accepts(candidate)) throw new IllegalStateException("STALE_WINDOW_OWNER");
    }

    private boolean sameWindow(String candidate) {
        return !isBlank(candidate) && candidate.equals(windowIdentity);
    }

    private static String requireText(String value, String name) {
        if (isBlank(value)) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
