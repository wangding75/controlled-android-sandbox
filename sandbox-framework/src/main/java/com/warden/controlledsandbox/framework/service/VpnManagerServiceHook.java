package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible VpnManager Binder replacement. */
public final class VpnManagerServiceHook {
    private VpnManagerServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "vpn_management", "vpn", identity,
                "mService", "mVpnManager", "sService");
    }
}
