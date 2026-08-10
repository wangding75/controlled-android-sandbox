package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fail-closed launch gate for configured Connectivity/DNS/Proxy/VPN virtualization. */
final class NetworkServiceProxyReadiness {
    private NetworkServiceProxyReadiness() { }

    static void require(Map<String, Boolean> installed, VirtualNetworkServiceProfileSnapshot profile) {
        require(installed, profile, true);
    }

    static void require(Map<String, Boolean> installed, VirtualNetworkServiceProfileSnapshot profile,
            boolean nativeHooksInstalled) {
        if (installed == null || profile == null) {
            throw new IllegalStateException("VIRTUAL_NETWORK_READINESS_INPUT_REQUIRED");
        }
        List<String> missing = new ArrayList<>();
        requireDomain(profile.connectivity().mode(), installed, missing, "connectivity");
        requireDomain(profile.dns().mode(), installed, missing, "dnsResolver");
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.dns().mode())
                && !nativeHooksInstalled && !missing.contains("dnsResolver")) {
            missing.add("dnsResolver");
        }
        requireDomain(profile.proxy().mode(), installed, missing, "connectivity");
        requireDomain(profile.vpn().mode(), installed, missing, "vpn");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("VIRTUAL_NETWORK_SERVICE_PROXY_REQUIRED:"
                    + String.join(",", missing));
        }
    }

    private static void requireDomain(String mode, Map<String, Boolean> installed,
            List<String> missing, String... hooks) {
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(mode)) return;
        for (String hook : hooks) if (!Boolean.TRUE.equals(installed.get(hook)) && !missing.contains(hook)) missing.add(hook);
    }
}
