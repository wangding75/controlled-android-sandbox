package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible DropBoxManager service projection. */
public final class DropBoxManagerServiceHook {
    private DropBoxManagerServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "dropbox", "com.android.internal.os.IDropBoxManagerService$Stub", identity, "dropbox");
    }
}
