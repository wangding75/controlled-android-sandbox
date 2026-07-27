package com.warden.controlledsandbox.framework.activity;

public enum LifecycleState {
    INITIALIZED,
    CREATED,
    STARTED,
    RESUMED,
    PAUSED,
    STOPPED,
    DESTROYED;

    public boolean canTransitionTo(LifecycleState next) {
        return switch (this) {
            case INITIALIZED -> next == CREATED || next == DESTROYED;
            case CREATED -> next == STARTED || next == DESTROYED;
            case STARTED -> next == RESUMED || next == STOPPED || next == DESTROYED;
            case RESUMED -> next == PAUSED || next == DESTROYED;
            case PAUSED -> next == RESUMED || next == STOPPED || next == DESTROYED;
            case STOPPED -> next == STARTED || next == DESTROYED;
            case DESTROYED -> false;
        };
    }
}
