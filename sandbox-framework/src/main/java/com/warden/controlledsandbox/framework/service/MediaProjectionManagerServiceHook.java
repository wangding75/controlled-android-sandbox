package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** MediaProjectionManager Binder hook. */
public final class MediaProjectionManagerServiceHook {
    private MediaProjectionManagerServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "media_projection", "android.media.projection.IMediaProjectionManager$Stub",
                identity, "mediaProjection");
    }
}
