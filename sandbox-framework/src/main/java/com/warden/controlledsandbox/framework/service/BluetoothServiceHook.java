package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

public final class BluetoothServiceHook {
    private BluetoothServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "bluetooth", "bluetooth", identity,
                "mService", "mManagerService", "mAdapter.mService", "mAdapter.mManagerService");
    }
}
