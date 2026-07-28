package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Per-Guest Clipboard proxy. Host clipboard content is never used as fallback. */
public final class ClipboardManagerHook {
    private ClipboardManagerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "clipboard", "clipboard", identity,
                "mService", "sService");
    }
}
