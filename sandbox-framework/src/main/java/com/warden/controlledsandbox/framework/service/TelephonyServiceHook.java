package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.DeviceServiceBindingRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Version-tolerant TelephonyManager Binder hooks. */
public final class TelephonyServiceHook {
    private TelephonyServiceHook() { }
    public static AutoCloseable installTelephony(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "telephony");
    }
    public static AutoCloseable installSubscriberInfo(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "phoneSubInfo");
    }
    public static AutoCloseable installRegistry(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "telephonyRegistry");
    }
    public static AutoCloseable installSubscription(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "subscription");
    }
}
