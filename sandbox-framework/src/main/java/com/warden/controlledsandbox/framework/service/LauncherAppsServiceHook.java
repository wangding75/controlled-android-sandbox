package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible LauncherApps Binder replacement. */
public final class LauncherAppsServiceHook {
    private LauncherAppsServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                context, "launcherapps", "launcherApps", "android.content.pm.ILauncherApps",
                identity, "mService", "sService");
    }
}
