package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** PersistentDataBlock Binder hook. */
public final class PersistentDataBlockServiceHook {
    private PersistentDataBlockServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "persistent_data_block",
                "android.service.persistentdata.IPersistentDataBlockService$Stub",
                identity, "persistentDataBlock");
    }
}
