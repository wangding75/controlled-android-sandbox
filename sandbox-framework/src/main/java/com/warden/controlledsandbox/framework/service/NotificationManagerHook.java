package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

public final class NotificationManagerHook {
    private NotificationManagerHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.staticField("android.app.NotificationManager", "sService", "getService", identity, "notification");
    }
}
