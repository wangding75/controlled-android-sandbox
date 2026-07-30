package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** CompanionDeviceManager Binder hook. */
public final class CompanionDeviceManagerServiceHook {
    private CompanionDeviceManagerServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "companiondevice", "android.companion.ICompanionDeviceManager$Stub",
                identity, "companionDevice");
    }
}
