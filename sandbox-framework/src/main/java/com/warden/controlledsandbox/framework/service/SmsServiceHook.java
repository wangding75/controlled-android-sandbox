package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible SMS Binder projection. */
public final class SmsServiceHook {
    private SmsServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "isms", "com.android.internal.telephony.ISms$Stub", identity, "isms");
    }
}
