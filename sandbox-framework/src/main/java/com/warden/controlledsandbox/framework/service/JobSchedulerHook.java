package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;

public final class JobSchedulerHook {
    private JobSchedulerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerField(context, "jobscheduler", "mBinder", identity);
    }
}
