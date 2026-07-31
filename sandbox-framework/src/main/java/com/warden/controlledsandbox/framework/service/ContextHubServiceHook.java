package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** ContextHub Binder hook. */
public final class ContextHubServiceHook {
    private ContextHubServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "contexthub", "android.hardware.location.IContextHubService$Stub",
                identity, "contextHub");
    }
}
