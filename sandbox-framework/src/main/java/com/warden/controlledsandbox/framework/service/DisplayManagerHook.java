package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible DisplayManagerGlobal Binder-field replacement. */
public final class DisplayManagerHook {
    private DisplayManagerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "display", "display",
                identity, "mGlobal.mDm", "mDm", "mGlobal.mDisplayManager");
    }
}
