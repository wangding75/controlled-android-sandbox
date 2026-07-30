package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible UsageStatsManager Binder replacement. */
public final class UsageStatsManagerServiceHook {
    private UsageStatsManagerServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "usagestats", "usageStats", identity,
                "mService", "sService");
    }
}
