package com.warden.controlledsandbox.framework.binder;

/**
 * Decides whether a Binder lease still belongs to the active Guest session generation.
 * Implementations must be side-effect free and fast because the check runs on every transact.
 */
@FunctionalInterface
public interface BinderSessionFence {
    BinderSessionFence ALWAYS_ACTIVE = identity -> true;

    boolean isActive(BinderIdentity identity);
}
