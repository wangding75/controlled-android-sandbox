package com.warden.controlledsandbox.framework.activity;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

public final class ActivityTaskManagerHook {
    private ActivityTaskManagerHook() { }
    static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.singleton("android.app.ActivityTaskManager", "IActivityTaskManagerSingleton", identity);
    }
}
