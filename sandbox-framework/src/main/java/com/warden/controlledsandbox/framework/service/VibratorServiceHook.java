package com.warden.controlledsandbox.framework.service;
import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
/** Reversible Vibrator/VibratorManager Binder replacement. */ public final class VibratorServiceHook {
    private VibratorServiceHook(){
    }
    public static AutoCloseable install(Context context, GuestIdentity identity)throws Exception{
        if (identity.isolatedProcess()) {
            com.warden.controlledsandbox.contract.VirtualPowerProfileSnapshot profile =
                    identity.virtualServices().policyServicesProfile().power();
            AutoCloseable vibrator = com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry
                    .install(context, Context.VIBRATOR_SERVICE, new android.os.ControlledVibrator(profile));
            try {
                AutoCloseable manager = com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry
                        .install(context, "vibrator_manager", new android.os.ControlledVibratorManager(profile));
                return () -> { try { manager.close(); } finally { vibrator.close(); } };
            } catch (Throwable error) {
                try { vibrator.close(); } catch (Exception rollback) { error.addSuppressed(rollback); }
                if (error instanceof Exception exception) throw exception;
                throw new IllegalStateException("VIRTUAL_VIBRATOR_MANAGER_INSTALL_FAILED", error);
            }
        }
        try{
            return ReflectiveServiceHook.managerFieldCandidates(context, "vibrator_manager", "vibrator", identity, "mService", "sService");
        } catch(Exception ignored){
            return ReflectiveServiceHook.managerFieldCandidates(context, "vibrator", "vibrator", identity, "mService", "sService");
        }
    }
}
