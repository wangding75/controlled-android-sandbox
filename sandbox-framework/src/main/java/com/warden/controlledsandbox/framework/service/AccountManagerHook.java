package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Bounded AccountManager isolation. Authenticator/OAuth flows remain fail-closed. */
public final class AccountManagerHook {
    private AccountManagerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                context, "account", "account", "android.accounts.IAccountManager", identity,
                "mService", "sService");
    }
}
