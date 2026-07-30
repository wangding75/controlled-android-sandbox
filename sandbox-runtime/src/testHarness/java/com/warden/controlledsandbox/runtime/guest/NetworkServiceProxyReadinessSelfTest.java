package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualConnectivityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualProxyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualVpnProfileSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fail-closed source tests for M5-T10 network proxy readiness. */
public final class NetworkServiceProxyReadinessSelfTest {
    public static void main(String[] args) {
        Map<String, Boolean> installed = new LinkedHashMap<>();
        installed.put("connectivity", true);
        installed.put("dnsResolver", true);
        installed.put("vpn", true);
        NetworkServiceProxyReadiness.require(installed, profile(VirtualLocationProfileSnapshot.MODE_STATIC));
        installed.put("dnsResolver", false);
        boolean blocked = false;
        try { NetworkServiceProxyReadiness.require(installed,
                profile(VirtualLocationProfileSnapshot.MODE_STATIC)); }
        catch (IllegalStateException expected) { blocked = expected.getMessage().contains("dnsResolver"); }
        require(blocked, "missing DNS resolver hook blocks launch");
        NetworkServiceProxyReadiness.require(Map.of(), profile(VirtualLocationProfileSnapshot.MODE_HOST));
        System.out.println("PASS M5-T10 network-service proxy readiness self-test");
    }

    private static VirtualNetworkServiceProfileSnapshot profile(String mode) {
        VirtualNetworkSnapshot network = new VirtualNetworkSnapshot(42, VirtualNetworkSnapshot.WIFI,
                true, true, false, false, false, "wlan0", 1500, 1000, 500,
                List.of("192.0.2.10/24"), List.of("192.0.2.53"), List.of("0.0.0.0/0"), "");
        return new VirtualNetworkServiceProfileSnapshot(1L, 1L,
                new VirtualConnectivityProfileSnapshot(mode, 42, false, false, 4, List.of(network)),
                new VirtualDnsProfileSnapshot(mode, List.of("192.0.2.53"), List.of(),
                        VirtualDnsProfileSnapshot.PRIVATE_DNS_OFF, "", false, List.of()),
                new VirtualProxyProfileSnapshot(mode, VirtualProxyProfileSnapshot.NONE,
                        "", 0, List.of(), "", false),
                new VirtualVpnProfileSnapshot(mode, VirtualVpnProfileSnapshot.DISCONNECTED,
                        "", false, List.of(), false, false, 0, "", List.of(), List.of(), List.of()));
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
