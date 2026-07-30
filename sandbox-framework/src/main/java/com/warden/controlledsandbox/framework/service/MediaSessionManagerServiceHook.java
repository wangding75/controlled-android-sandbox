package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible MediaSession service projection. */
public final class MediaSessionManagerServiceHook {
    private MediaSessionManagerServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "media_session", "android.media.session.ISessionManager$Stub", identity, "mediasession");
    }
}
