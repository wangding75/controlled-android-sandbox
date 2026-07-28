package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

public final class LocationServiceHook {
    private LocationServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "location", "location", identity,
                "mService", "mLocationManagerService");
    }
}
