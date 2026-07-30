package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** PrintManager Binder hook. */
public final class PrintManagerServiceHook {
    private PrintManagerServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "print", "android.print.IPrintManager$Stub", identity, "print");
    }
}
