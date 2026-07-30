package com.warden.controlledsandbox.framework.service;
import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.util.ArrayList;
import java.util.List;
/** Reversible biometric/fingerprint Binder replacements. */ public final class BiometricServiceHook {
    private BiometricServiceHook(){
    }
    public static AutoCloseable install(GuestIdentity identity)throws Exception{
        List<AutoCloseable> hooks=new ArrayList<>();
        Exception failure=null;
        for(String[] value:new String[][]{
            {
                "biometric", "biometric"
            }
            , {
                "fingerprint", "fingerprint"
            }
        }
        ){
            try{
                hooks.add(ServiceManagerBinderHook.installDiscovered(value[0], identity, value[1]));
            } catch(Exception error){
                if(failure==null)failure=error;
                else failure.addSuppressed(error);
            }
        }
        if(hooks.isEmpty())throw failure==null?new IllegalStateException("BIOMETRIC_SERVICES_UNAVAILABLE"):failure;
        return()->{
            Exception closeFailure=null;
            for(int i=hooks.size()-1; i>=0; i--)try{
                hooks.get(i).close();
            } catch(Exception error){
                if(closeFailure==null)closeFailure=error;
                else closeFailure.addSuppressed(error);
            }
            if(closeFailure!=null)throw closeFailure;
        };
    }
}
