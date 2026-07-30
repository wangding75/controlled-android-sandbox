package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Source-level Google service-broker hook; availability is GMS-version dependent. */
public final class GoogleServiceBrokerHook {
    private GoogleServiceBrokerHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "gms",
                "com.google.android.gms.common.api.internal.IGmsServiceBroker$Stub",
                identity,
                "gms");
    }
}
