package com.warden.controlledsandbox.domain.session;

/** Explicit states for a process-bound guest session. */
public enum SessionState {
    ALLOCATED,
    PREPARING,
    READY,
    ACTIVE,
    RECOVERING,
    STOPPING,
    STOPPED,
    FAILED;

    public boolean canTransitionTo(SessionState next) {
        if (next == null || next == this) return false;
        switch (this) {
            case ALLOCATED: return next == PREPARING || next == STOPPING || next == FAILED;
            case PREPARING: return next == READY || next == FAILED || next == STOPPING;
            case READY: return next == ACTIVE || next == RECOVERING || next == STOPPING || next == FAILED;
            case ACTIVE: return next == READY || next == RECOVERING || next == STOPPING || next == FAILED;
            case RECOVERING: return next == PREPARING || next == STOPPING || next == FAILED;
            case STOPPING: return next == STOPPED || next == FAILED;
            case STOPPED:
            case FAILED:
            default: return false;
        }
    }
}
