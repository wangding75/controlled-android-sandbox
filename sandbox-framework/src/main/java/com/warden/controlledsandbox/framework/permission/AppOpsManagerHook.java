package com.warden.controlledsandbox.framework.permission;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;

public final class AppOpsManagerHook {
    private AppOpsManagerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerField(context, "appops", "mService", identity);
    }
}
