package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualConnectivityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsRecordSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualProxyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualVpnProfileSnapshot;
import java.util.List;

/** Deterministic host-independent Java network defaults. */
final class VirtualNetworkServiceDefaults {
    private VirtualNetworkServiceDefaults() { }

    static VirtualNetworkServiceProfileSnapshot create(String packageName, int virtualUserId,
            long version, long updatedAtMs) {
        int networkId = 1000 + virtualUserId;
        int host = 10 + Math.floorMod(packageName.hashCode() + virtualUserId, 190);
        String address = "192.0.2." + host + "/24";
        VirtualNetworkSnapshot network = new VirtualNetworkSnapshot(networkId,
                VirtualNetworkSnapshot.WIFI, true, true, false, false, false,
                "wlan" + Math.floorMod(virtualUserId, 10), 1500, 100_000, 50_000,
                List.of(address), List.of("192.0.2.53"),
                List.of("0.0.0.0/0 via 192.0.2.1", "192.0.2.0/24"), "sandbox.invalid");
        VirtualConnectivityProfileSnapshot connectivity = new VirtualConnectivityProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, networkId, false, false, 16,
                List.of(network));
        VirtualDnsProfileSnapshot dns = new VirtualDnsProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, List.of("192.0.2.53"),
                List.of("sandbox.invalid"), VirtualDnsProfileSnapshot.PRIVATE_DNS_OFF,
                "", false, List.of(
                        new VirtualDnsRecordSnapshot("sandbox.invalid", VirtualDnsRecordSnapshot.A,
                                List.of("192.0.2." + host), 300),
                        new VirtualDnsRecordSnapshot("localhost", VirtualDnsRecordSnapshot.A,
                                List.of("127.0.0.1"), 60)));
        VirtualProxyProfileSnapshot proxy = new VirtualProxyProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, VirtualProxyProfileSnapshot.NONE,
                "", 0, List.of(), "", false);
        VirtualVpnProfileSnapshot vpn = new VirtualVpnProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, VirtualVpnProfileSnapshot.DISCONNECTED,
                "", false, List.of(), false, false, 0, "", List.of(), List.of(), List.of());
        return new VirtualNetworkServiceProfileSnapshot(version, updatedAtMs,
                connectivity, dns, proxy, vpn);
    }
}
