package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible UserManager Binder replacement. */
public final class UserManagerServiceHook {
    private UserManagerServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                context, "user", "userManager", "android.os.IUserManager", identity,
                "mService", "sService");
    }
}
