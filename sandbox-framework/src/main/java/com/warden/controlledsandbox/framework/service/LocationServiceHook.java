package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.DeviceServiceBindingRegistry;
import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

public final class LocationServiceHook {
    private LocationServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "location");
    }

    public static AutoCloseable installGuestManager(Context guestContext, GuestIdentity identity) {
        android.location.ControlledLocationManager manager =
                new android.location.ControlledLocationManager(
                        () -> identity.virtualServices().deviceServiceProfile().location());
        return GuestSystemServiceOverrideRegistry.install(
                guestContext, Context.LOCATION_SERVICE, manager);
    }
}
