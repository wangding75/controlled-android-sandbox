package com.warden.controlledsandbox.framework.service;
import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
/** Reversible AutofillManager Binder replacement. */ public final class AutofillManagerServiceHook {
    private AutofillManagerServiceHook(){
    }
    public static AutoCloseable install(Context context, GuestIdentity identity)throws Exception{
        return ReflectiveServiceHook.managerFieldCandidates(context, "autofill", "autofill", identity, "mService", "sService");
    }
}
