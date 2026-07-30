package com.warden.controlledsandbox.framework.service;
import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
/** Reversible DevicePolicyManager Binder replacement. */ public final class DevicePolicyManagerServiceHook {
    private DevicePolicyManagerServiceHook(){
    }
    public static AutoCloseable install(Context context, GuestIdentity identity)throws Exception{
        return ReflectiveServiceHook.managerFieldCandidates(context, "device_policy", "devicePolicy", identity, "mService", "sService");
    }
}
