package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import android.os.Build;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible InputMethodManager Binder-field replacement. */
public final class InputMethodManagerHook {
    private InputMethodManagerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        AutoCloseable managerHook = ReflectiveServiceHook.managerFieldCandidates(
                context, "input_method", "inputMethod",
                identity, "mService", "mImm.mService", "mDelegate.mService");
        if (Build.VERSION.SDK_INT < 34) return managerHook;

        // Android 14 routes public InputMethodManager queries through the process-global
        // IInputMethodManagerGlobalInvoker cache instead of the legacy manager mService field.
        // Resolve the lazy cache through its platform accessor before replacing it so later
        // display-specific manager instances cannot bypass the Guest identity boundary.
        AutoCloseable globalHook = ReflectiveServiceHook.globalInputMethodServiceCache(identity);
        return ReflectiveServiceHook.compose(managerHook, globalHook);
    }
}
