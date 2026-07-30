package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible MediaRouter service projection. */
public final class MediaRouterServiceHook {
    private MediaRouterServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "media_router", "android.media.IMediaRouterService$Stub", identity, "mediarouter");
    }
}
