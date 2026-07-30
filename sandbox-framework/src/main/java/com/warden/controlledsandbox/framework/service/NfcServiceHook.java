package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** NFC Binder hook. */
public final class NfcServiceHook {
    private NfcServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "nfc", "android.nfc.INfcAdapter$Stub", identity, "nfc");
    }
}
