package com.warden.controlledsandbox.framework.service;

import android.os.Build;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/**
 * Descriptor-validated DNS boundary for both platform families. API 32 exposes the stable
 * {@code dnsresolver}/{@code android.net.IDnsResolver} Binder; API 35 exposes no resolver Binder
 * and routes {@code android.net.DnsResolver} through the process-native network boundary.
 */
public final class DnsResolverServiceHook {
    private DnsResolverServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return install(identity, false);
    }

    public static AutoCloseable install(GuestIdentity identity, boolean nativeHooksInstalled)
            throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            if (Build.VERSION.SDK_INT < 35) {
                // API 32 keeps this system Binder for the resolver daemon, while the app-facing
                // DnsResolver still enters NetworkUtils/native code directly.
                ReflectiveServiceHook.validateServiceManagerDescriptor(
                        "dnsresolver", "android.net.IDnsResolver");
            }
            return installNativeFacade(identity, nativeHooksInstalled);
        }
        try {
            return ReflectiveServiceHook.staticInstanceFieldCandidatesWithDescriptor(
                    "android.net.DnsResolver", "getInstance", identity, "dnsresolver",
                    "android.net.IDnsResolver", "mDnsResolver", "mResolver", "mService");
        } catch (Throwable fieldFailure) {
            try {
                return ReflectiveServiceHook.serviceManagerBinding(
                        "dnsresolver", "dnsresolver", "android.net.IDnsResolver", identity);
            } catch (Throwable serviceFailure) {
                fieldFailure.addSuppressed(serviceFailure);
                if (fieldFailure instanceof Exception exception) throw exception;
                throw new IllegalStateException("DNS_RESOLVER_INSTALL_FAILED", fieldFailure);
            }
        }
    }

    private static AutoCloseable installNativeFacade(
            GuestIdentity identity, boolean nativeHooksInstalled) throws Exception {
        VirtualNetworkServiceProfileSnapshot profile = identity.virtualServices().networkServiceProfile();
        if (profile == null) throw new IllegalStateException("VIRTUAL_NETWORK_PROFILE_REQUIRED");
        String mode = profile.dns().mode();
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(mode)) {
            throw new SecurityException("VIRTUAL_DNS_BLOCKED");
        }
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(mode) && !nativeHooksInstalled) {
            throw new IllegalStateException("DNS_NATIVE_NETWORK_HOOK_REQUIRED");
        }

        Class<?> resolverClass = Class.forName("android.net.DnsResolver");
        java.lang.reflect.Method getter = resolverClass.getDeclaredMethod("getInstance");
        getter.setAccessible(true);
        Object resolver = getter.invoke(null);
        if (resolver == null) throw new IllegalStateException("DNS_RESOLVER_INSTANCE_MISSING");
        boolean query = false;
        boolean rawQuery = false;
        for (java.lang.reflect.Method method : resolverClass.getMethods()) {
            if (method.getName().equals("query")) query = true;
            if (method.getName().equals("rawQuery")) rawQuery = true;
        }
        if (!query || !rawQuery) {
            throw new IllegalStateException("DNS_RESOLVER_CONTRACT_UNSUPPORTED:query/rawQuery");
        }
        android.util.Log.i("CS_NETWORK_PROXY", "DNS_RESOLVER_READY api=" + Build.VERSION.SDK_INT
                + " entry=android.net.DnsResolver"
                + " boundary=NativePolicy.installHooks virtualDnsMode=" + mode);
        return new FacadeBinding();
    }

    /** The mutable boundary is the native hook; this handle records its verified facade contract. */
    private static final class FacadeBinding implements AutoCloseable {
        private boolean active = true;
        @Override public void close() { active = false; }
    }
}
