package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible ShortcutManager Binder replacement. */
public final class ShortcutManagerServiceHook {
    private ShortcutManagerServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                context, "shortcut", "shortcut", "android.content.pm.IShortcutService",
                identity, "mService", "sService");
    }
}
