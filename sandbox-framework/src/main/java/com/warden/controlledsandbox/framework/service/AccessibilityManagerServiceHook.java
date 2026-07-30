package com.warden.controlledsandbox.framework.service;
import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
/** Reversible AccessibilityManager Binder replacement. */ public final class AccessibilityManagerServiceHook {
    private AccessibilityManagerServiceHook(){
    }
    public static AutoCloseable install(Context context, GuestIdentity identity)throws Exception{
        return ReflectiveServiceHook.managerFieldCandidates(context, "accessibility", "accessibility", identity, "mService", "sService");
    }
}
