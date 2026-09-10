package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import android.os.Build;
import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;

/**
 * Exposes the platform LocaleManager through the Guest service boundary.
 *
 * <p>NBB's Context wrappers delegate services which do not have a dedicated virtual proxy to
 * their base Context.  LocaleManager is such a service in the checked NBB/VA sources.  Keep that
 * behavior explicit and readiness-gated here: the Host must materialize a non-null manager before
 * Guest code can observe it.  A future locale-specific virtual proxy can replace this hook
 * without widening GuestContext's unbounded Host fallback.</p>
 */
public final class LocaleServiceHook {
    private static final String SERVICE = "locale";

    private LocaleServiceHook() { }

    public static AutoCloseable install(Context guestContext, Context hostContext) {
        if (guestContext == null) throw new IllegalArgumentException("guestContext is required");
        if (hostContext == null) throw new IllegalArgumentException("hostContext is required");
        if (Build.VERSION.SDK_INT < 33) return () -> { };
        Object manager = hostContext.getSystemService(SERVICE);
        if (manager == null) throw new IllegalStateException("LOCALE_MANAGER_UNAVAILABLE");
        return GuestSystemServiceOverrideRegistry.install(guestContext, SERVICE, manager);
    }
}
