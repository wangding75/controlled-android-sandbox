package com.warden.controlledsandbox.framework.service;
import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
/** Reversible SensorPrivacy Binder replacement. */ public final class SensorPrivacyServiceHook {
    private SensorPrivacyServiceHook(){
    }
    public static AutoCloseable install(GuestIdentity identity)throws Exception{
        return ServiceManagerBinderHook.installDiscovered("sensor_privacy", identity, "sensorPrivacy");
    }
}
