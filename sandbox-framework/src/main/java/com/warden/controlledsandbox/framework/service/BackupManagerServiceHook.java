package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible BackupManager service projection. */
public final class BackupManagerServiceHook {
    private BackupManagerServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "backup", "android.app.backup.IBackupManager$Stub", identity, "backup");
    }
}
