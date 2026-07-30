package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible DnsResolver Binder replacement where the platform exposes a resolver delegate. */
public final class DnsResolverServiceHook {
    private DnsResolverServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.staticInstanceFieldCandidates("android.net.DnsResolver", "getInstance",
                identity, "dnsresolver", "mDnsResolver", "mResolver", "mService");
    }
}
