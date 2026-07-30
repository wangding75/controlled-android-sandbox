package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible WindowManagerGlobal service proxy; openSession results are wrapped separately. */
public final class WindowManagerHook {
    private WindowManagerHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.staticField("android.view.WindowManagerGlobal",
                "sWindowManagerService", "getWindowManagerService", identity, "window");
    }
}
