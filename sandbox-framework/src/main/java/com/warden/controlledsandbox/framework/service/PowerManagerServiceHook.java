package com.warden.controlledsandbox.framework.service;
import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
/** Reversible PowerManager Binder replacement. */ public final class PowerManagerServiceHook {
    private PowerManagerServiceHook(){
    }
    public static AutoCloseable install(Context context, GuestIdentity identity)throws Exception{
        return ReflectiveServiceHook.managerFieldCandidates(context, "power", "power", identity, "mService", "sService");
    }
}
