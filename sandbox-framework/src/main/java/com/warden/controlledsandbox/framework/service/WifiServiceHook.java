package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.DeviceServiceBindingRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

public final class WifiServiceHook {
    private WifiServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "wifi");
    }
    public static AutoCloseable installScanner(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "wifiScanner");
    }
}
