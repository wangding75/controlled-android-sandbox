package com.warden.controlledsandbox.domain.session;

import java.util.Objects;

/** Immutable snapshot of a sandbox runtime process session. */
public final class GuestSession {
    private final String sessionId;
    private final String packageName;
    private final int virtualUserId;
    private final String processName;
    private final int processSlot;
    private final long generation;
    private final SessionState state;
    private final long updatedAtMs;
    private final String failure;

    public GuestSession(String sessionId, String packageName, int virtualUserId, int processSlot,
                        long generation, SessionState state, long updatedAtMs, String failure) {
        this(sessionId, packageName, virtualUserId, packageName, processSlot,
                generation, state, updatedAtMs, failure);
    }

    public GuestSession(String sessionId, String packageName, int virtualUserId, String processName,
                        int processSlot, long generation, SessionState state,
                        long updatedAtMs, String failure) {
        this.sessionId = requireText(sessionId, "sessionId");
        this.packageName = requireText(packageName, "packageName");
        this.processName = requireText(processName, "processName");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (processSlot < 0) throw new IllegalArgumentException("processSlot must be non-negative");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        this.virtualUserId = virtualUserId;
        this.processSlot = processSlot;
        this.generation = generation;
        this.state = Objects.requireNonNull(state, "state");
        this.updatedAtMs = updatedAtMs;
        this.failure = failure == null ? "" : failure;
    }

    public String sessionId() { return sessionId; }
    public String packageName() { return packageName; }
    public int virtualUserId() { return virtualUserId; }
    public String processName() { return processName; }
    public int processSlot() { return processSlot; }
    public long generation() { return generation; }
    public SessionState state() { return state; }
    public long updatedAtMs() { return updatedAtMs; }
    public String failure() { return failure; }

    public GuestSession transition(SessionState next, long nowMs, String failureReason) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid session transition " + state + " -> " + next);
        }
        return new GuestSession(sessionId, packageName, virtualUserId, processName, processSlot,
                generation, next, nowMs, failureReason);
    }

    public GuestSession nextGeneration(long nowMs) {
        if (state != SessionState.RECOVERING) {
            throw new IllegalStateException("Generation may advance only while recovering");
        }
        return new GuestSession(sessionId, packageName, virtualUserId, processName, processSlot,
                generation + 1, SessionState.PREPARING, nowMs, "");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
