package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** SystemUpdateManager Binder hook. */
public final class SystemUpdateServiceHook {
    private SystemUpdateServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "system_update", "android.os.ISystemUpdateManager$Stub",
                identity, "systemUpdate");
    }
}
