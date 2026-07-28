package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Guest-generation alarm lifecycle kept outside the host AlarmManager namespace. */
public final class AlarmManagerHook {
    private AlarmManagerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "alarm", "alarm", identity,
                "mService", "sService");
    }
}
