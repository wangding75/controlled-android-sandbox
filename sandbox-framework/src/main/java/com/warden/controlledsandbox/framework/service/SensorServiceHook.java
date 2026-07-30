package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.SensorCatalogHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

public final class SensorServiceHook {
    private SensorServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return SensorCatalogHook.install(context, identity);
    }
}
