package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

public final class WifiServiceHook {
    private WifiServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "wifi", "wifi", identity,
                "mService", "mWifiService", "sService");
    }
    public static AutoCloseable installScanner(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "wifiscanner", "wifiscanner", identity,
                "mService", "mWifiScannerService", "sService");
    }
}
