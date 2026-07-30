package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** ActivityClient lifecycle bridge used on Android versions exposing INTERFACE_SINGLETON. */
public final class ActivityClientHook {
    private ActivityClientHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.singleton("android.app.ActivityClient",
                "INTERFACE_SINGLETON", identity, "activityClient");
    }
}
