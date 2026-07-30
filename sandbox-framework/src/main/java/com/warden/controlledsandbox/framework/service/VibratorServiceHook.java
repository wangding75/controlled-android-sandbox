package com.warden.controlledsandbox.framework.service;
import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
/** Reversible Vibrator/VibratorManager Binder replacement. */ public final class VibratorServiceHook {
    private VibratorServiceHook(){
    }
    public static AutoCloseable install(Context context, GuestIdentity identity)throws Exception{
        try{
            return ReflectiveServiceHook.managerFieldCandidates(context, "vibrator_manager", "vibrator", identity, "mService", "sService");
        } catch(Exception ignored){
            return ReflectiveServiceHook.managerFieldCandidates(context, "vibrator", "vibrator", identity, "mService", "sService");
        }
    }
}
