package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible AppWidgetManager Binder replacement. */
public final class AppWidgetManagerServiceHook {
    private AppWidgetManagerServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "appwidget", "appWidget", identity,
                "mService", "sService");
    }
}
