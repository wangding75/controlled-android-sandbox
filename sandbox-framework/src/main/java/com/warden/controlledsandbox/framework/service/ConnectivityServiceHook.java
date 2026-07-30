package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible ConnectivityManager Binder replacement. */
public final class ConnectivityServiceHook {
    private ConnectivityServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "connectivity", "connectivity", identity,
                "mService", "mConnectivityManager", "sService");
    }
}
