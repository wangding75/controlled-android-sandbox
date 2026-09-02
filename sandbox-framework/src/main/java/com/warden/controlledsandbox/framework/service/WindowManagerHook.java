package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible WindowManagerGlobal service proxy; openSession results are wrapped separately. */
public final class WindowManagerHook {
    private WindowManagerHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        AutoCloseable hook = ReflectiveServiceHook.staticField("android.view.WindowManagerGlobal",
                "sWindowManagerService", "getWindowManagerService", identity, "window");
        // A reference IWindowManager proxy drops the cached IWindowManager so the next
        // openSession goes through the hooked manager. Drop a pre-hook sWindowSession the same way;
        // otherwise the first addView uses an unhooked session and WMS never sees host packageName.
        try {
            java.lang.reflect.Field session = Class.forName("android.view.WindowManagerGlobal")
                    .getDeclaredField("sWindowSession");
            session.setAccessible(true);
            session.set(null, null);
        } catch (NoSuchFieldError | NoSuchFieldException ignored) {
            // API/OEM images without a static session cache keep the hooked IWindowManager path.
        }
        return hook;
    }
}
