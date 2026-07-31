package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** StorageStatsManager Binder hook. */
public final class StorageStatsManagerServiceHook {
    private StorageStatsManagerServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "storagestats", "android.app.usage.IStorageStatsManager$Stub",
                identity, "storageStats");
    }
}
