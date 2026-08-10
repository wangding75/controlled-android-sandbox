package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;

public final class StorageManagerHook {
    private StorageManagerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                context, "storage", "storage", "android.os.storage.IStorageManager", identity,
                java.util.List.of("storage", "mount"),
                "mStorageManager");
    }
}
