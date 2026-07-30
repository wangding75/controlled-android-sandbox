package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible RestrictionsManager Binder replacement. */
public final class RestrictionsManagerServiceHook {
    private RestrictionsManagerServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "restrictions",
                "android.content.IRestrictionsManager$Stub",
                identity,
                "restrictions");
    }
}
