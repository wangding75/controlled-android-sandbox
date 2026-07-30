package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible InputMethodManager Binder-field replacement. */
public final class InputMethodManagerHook {
    private InputMethodManagerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "input_method", "inputMethod",
                identity, "mService", "mImm.mService", "mDelegate.mService");
    }
}
