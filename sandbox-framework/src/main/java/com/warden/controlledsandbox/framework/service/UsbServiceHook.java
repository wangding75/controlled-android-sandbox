package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** USB Binder hook. */
public final class UsbServiceHook {
    private UsbServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "usb", "android.hardware.usb.IUsbManager$Stub", identity, "usb");
    }
}
