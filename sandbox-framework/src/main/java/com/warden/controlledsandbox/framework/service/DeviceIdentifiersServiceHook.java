package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible device-identifiers policy service projection. */
public final class DeviceIdentifiersServiceHook {
    private DeviceIdentifiersServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "device_identifiers",
                "android.os.IDeviceIdentifiersPolicyService$Stub",
                identity,
                "deviceidentifiers");
    }
}
