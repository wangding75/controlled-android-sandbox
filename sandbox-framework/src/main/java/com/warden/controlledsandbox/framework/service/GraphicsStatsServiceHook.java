package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** GraphicsStats Binder hook. */
public final class GraphicsStatsServiceHook {
    private GraphicsStatsServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "graphicsstats", "android.view.IGraphicsStats$Stub", identity, "graphicsStats");
    }
}
