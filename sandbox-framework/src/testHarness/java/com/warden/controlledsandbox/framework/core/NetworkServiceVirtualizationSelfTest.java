package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualConnectivityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplayProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplaySnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsRecordSnapshot;
import com.warden.controlledsandbox.contract.VirtualInputMethodProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualProxyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualVpnProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-side deterministic tests for M5-T10 network-service virtualization. */
public final class NetworkServiceVirtualizationSelfTest {
    public static void main(String[] args) {
        GuestIdentity identity = identity(profile(VirtualLocationProfileSnapshot.MODE_STATIC));
        testConnectivity(identity);
        testDns(identity);
        testVpn(identity);
        testHostMode();
        identity.networks().close();
        require(identity.networks().callbackCount() == 0 && identity.networks().vpnSessionCount() == 0,
                "network state closes deterministically");
        System.out.println("PASS M5-T10 network-service virtualization self-test");
    }

    private static void testConnectivity(GuestIdentity identity) {
        ConnectivityDelegate delegate = new ConnectivityDelegate();
        ConnectivityApi api = proxy(ConnectivityApi.class, delegate, identity, "connectivity");
        FakeNetwork network = api.getActiveNetwork();
        require(network.netId == 42 && delegate.calls == 0, "active network is virtualized");
        require(api.getAllNetworks().length == 1 && api.getAllNetworks()[0].netId == 42,
                "network list is virtualized");
        FakeCapabilities capabilities = api.getNetworkCapabilities(network);
        require(capabilities.downstreamKbps == 12000 && capabilities.upstreamKbps == 6000,
                "network capabilities are projected");
        FakeLinkProperties link = api.getLinkProperties(network);
        require("vnet0".equals(link.interfaceName) && link.mtu == 1400
                        && List.of("192.0.2.20/24").equals(link.addresses),
                "link properties are projected: iface=" + link.interfaceName
                        + ",mtu=" + link.mtu + ",addresses=" + link.addresses);
        require(api.isActiveNetworkMetered() && api.getRestrictBackgroundStatus() == 3,
                "metering and background restriction are virtualized");
        FakeProxyInfo proxy = api.getGlobalProxy();
        require("proxy.sandbox.invalid".equals(proxy.host) && proxy.port == 8080,
                "proxy identity is virtualized");
        NetworkCallback callback = new NetworkCallback();
        api.registerDefaultNetworkCallback(callback);
        require(callback.available == 42 && callback.capabilities == 12000
                        && "vnet0".equals(callback.interfaceName)
                        && identity.networks().callbackCount() == 1,
                "network callback is dispatched and owned");
        boolean limited = false;
        try { api.registerDefaultNetworkCallback(new NetworkCallback()); }
        catch (IllegalStateException expected) { limited = expected.getMessage().contains("CALLBACK_LIMIT"); }
        require(limited, "network callback limit is enforced");
        api.unregisterNetworkCallback(callback);
        require(identity.networks().callbackCount() == 0, "network callback is released");
        boolean callbackFailed = false;
        try { api.registerDefaultNetworkCallback(new ThrowingNetworkCallback()); }
        catch (IllegalStateException expected) { callbackFailed = expected.getMessage().contains("CALLBACK_FAILED"); }
        require(callbackFailed && identity.networks().callbackCount() == 0,
                "failed network callback registration rolls back ownership");
        boolean mutationDenied = false;
        try { api.bindProcessToNetwork(network); }
        catch (SecurityException expected) { mutationDenied = expected.getMessage().contains("MUTATION_DENIED"); }
        require(mutationDenied, "process network binding fails closed");
    }

    private static void testDns(GuestIdentity identity) {
        DnsDelegate delegate = new DnsDelegate();
        DnsApi api = proxy(DnsApi.class, delegate, identity, "dnsresolver");
        DnsCallback answer = new DnsCallback();
        api.query("api.sandbox.invalid", 1, answer);
        require(answer.values.equals(List.of("192.0.2.80")) && answer.error == -1 && delegate.calls == 0,
                "synthetic DNS answer is dispatched");
        DnsCallback missing = new DnsCallback();
        api.query("missing.sandbox.invalid", 1, missing);
        require(missing.error == 3, "missing DNS record returns NXDOMAIN");
        DnsCallback missingAaaa = new DnsCallback();
        api.query("api.sandbox.invalid", 28, missingAaaa);
        require(missingAaaa.error == 3 && missingAaaa.values.isEmpty(),
                "AAAA query never receives an IPv4 A-record fallback");
        boolean rawDenied = false;
        try { api.rawQuery("api.sandbox.invalid", 1, new DnsCallback()); }
        catch (SecurityException expected) { rawDenied = expected.getMessage().contains("RAW_QUERY_DENIED"); }
        require(rawDenied, "raw DNS query fails closed");
    }

    private static void testVpn(GuestIdentity identity) {
        VpnDelegate delegate = new VpnDelegate();
        VpnApi api = proxy(VpnApi.class, delegate, identity, "vpn");
        require(api.prepareVpn() && "DISCONNECTED".equals(api.getVpnState())
                        && "vpn.owner".equals(api.getAlwaysOnVpnPackage()) && api.isVpnLockdownEnabled(),
                "VPN query and preparation policy are virtualized");
        Object first = new Object();
        require(api.establishVpn(first) && identity.networks().vpnSessionCount() == 1,
                "VPN source session is reserved");
        boolean limited = false;
        try { api.establishVpn(new Object()); }
        catch (IllegalStateException expected) { limited = expected.getMessage().contains("SESSION_LIMIT"); }
        require(limited, "VPN session limit is enforced");
        api.stopVpn(first);
        require(identity.networks().vpnSessionCount() == 0 && delegate.calls == 0,
                "VPN source session is released without host mutation");
    }

    private static void testHostMode() {
        GuestIdentity identity = identity(profile(VirtualLocationProfileSnapshot.MODE_HOST));
        ConnectivityDelegate delegate = new ConnectivityDelegate();
        ConnectivityApi api = proxy(ConnectivityApi.class, delegate, identity, "connectivity");
        require(api.getActiveNetwork().netId == 999 && delegate.calls == 1,
                "HOST connectivity mode passes through");
        DnsDelegate dnsDelegate = new DnsDelegate();
        DnsApi dns = proxy(DnsApi.class, dnsDelegate, identity, "dnsresolver");
        dns.query("host.example", 1, new DnsCallback());
        require(dnsDelegate.calls == 1, "HOST DNS mode passes through");

        VirtualNetworkServiceProfileSnapshot mixed = profile(VirtualLocationProfileSnapshot.MODE_HOST);
        mixed = new VirtualNetworkServiceProfileSnapshot(mixed.policyVersion(), mixed.updatedAtMs(),
                mixed.connectivity(), mixed.dns(),
                new VirtualProxyProfileSnapshot(VirtualLocationProfileSnapshot.MODE_STATIC,
                        VirtualProxyProfileSnapshot.STATIC, "mixed.proxy.invalid", 3128, List.of(), "", false),
                mixed.vpn());
        GuestIdentity mixedIdentity = identity(mixed);
        ConnectivityDelegate mixedDelegate = new ConnectivityDelegate();
        ConnectivityApi mixedApi = proxy(ConnectivityApi.class, mixedDelegate, mixedIdentity, "connectivity");
        require(mixedApi.getActiveNetwork().netId == 999 && mixedDelegate.calls == 1,
                "HOST connectivity remains passthrough in mixed mode");
        require("mixed.proxy.invalid".equals(mixedApi.getGlobalProxy().host) && mixedDelegate.calls == 1,
                "STATIC proxy remains virtualized when Connectivity mode is HOST");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T delegate, GuestIdentity identity, String service) {
        return (T) Proxy.newProxyInstance(NetworkServiceVirtualizationSelfTest.class.getClassLoader(),
                new Class<?>[]{type}, new SystemServiceInvocationHandler(delegate, identity, service));
    }

    private static GuestIdentity identity(VirtualNetworkServiceProfileSnapshot network) {
        ApplicationInfo info = new ApplicationInfo(); info.packageName = "guest.pkg"; info.uid = 12001;
        Set<String> permissions = Set.of();
        return new GuestIdentity("guest.pkg", 12001, info, permissions, "host.pkg", 10001,
                new VirtualPackageMetadata("guest.pkg", "", info, List.of()), "guest.pkg", 0, 10L,
                new VirtualPermissionPolicy(permissions, Map.of(), permissions),
                new SandboxAppOpsPolicy(Map.of()), event -> { }, new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(deviceProfile(), interactionProfile(), network), "revision-m5-t10");
    }

    private static VirtualNetworkServiceProfileSnapshot profile(String mode) {
        VirtualNetworkSnapshot network = new VirtualNetworkSnapshot(42, VirtualNetworkSnapshot.WIFI,
                true, true, true, false, false, "vnet0", 1400, 12000, 6000,
                List.of("192.0.2.20/24"), List.of("192.0.2.53"),
                List.of("0.0.0.0/0 via 192.0.2.1"), "sandbox.invalid");
        return new VirtualNetworkServiceProfileSnapshot(1L, 1L,
                new VirtualConnectivityProfileSnapshot(mode, 42, false, true, 1, List.of(network)),
                new VirtualDnsProfileSnapshot(mode, List.of("192.0.2.53"), List.of("sandbox.invalid"),
                        VirtualDnsProfileSnapshot.PRIVATE_DNS_OFF, "", false,
                        List.of(new VirtualDnsRecordSnapshot("api.sandbox.invalid", VirtualDnsRecordSnapshot.A,
                                List.of("192.0.2.80"), 120))),
                new VirtualProxyProfileSnapshot(mode, VirtualProxyProfileSnapshot.STATIC,
                        "proxy.sandbox.invalid", 8080, List.of("localhost"), "", false),
                new VirtualVpnProfileSnapshot(mode, VirtualVpnProfileSnapshot.DISCONNECTED,
                        "vpn.owner", true, List.of("guest.pkg"), true, true, 1,
                        "tun0", List.of("198.51.100.2/32"), List.of("0.0.0.0/0"), List.of("198.51.100.53")));
    }

    private static VirtualDeviceServiceProfileSnapshot deviceProfile() {
        String mode = VirtualLocationProfileSnapshot.MODE_HOST;
        return new VirtualDeviceServiceProfileSnapshot(1L, 1L,
                new VirtualLocationProfileSnapshot(mode, "gps", false, 0, 0, 0, 1f,
                        0, 0, 0, 0, 1000, false, 0, 0, ""),
                new VirtualDeviceIdentitySnapshot(mode, "", "", "", false, "", "", "",
                        "", "", "", "", "", ""),
                new VirtualTelephonyProfileSnapshot(mode, -1, -1, false, false, false, List.of()),
                new VirtualWifiProfileSnapshot(mode, false, "", "", "", 0, -1, 0,
                        -127, 0, false, false, List.of()),
                new VirtualBluetoothProfileSnapshot(mode, false, 10, "", "", false, List.of(), List.of()),
                new VirtualSensorProfileSnapshot(mode, 60, List.of()));
    }
    private static VirtualInteractionProfileSnapshot interactionProfile() {
        String mode = VirtualWindowPolicySnapshot.MODE_HOST;
        return new VirtualInteractionProfileSnapshot(1L, 1L,
                new VirtualWindowPolicySnapshot(mode, 1, true, true, false, false),
                new VirtualInputMethodProfileSnapshot(mode, "", List.of(), false, false, false, true, 1),
                new VirtualDisplayProfileSnapshot(mode, 0, false, 0,
                        List.of(new VirtualDisplaySnapshot(0, "Host display", 1080, 1920,
                                420, 420f, 420f, 60f, 0, 2, 0, false))));
    }

    public interface ConnectivityApi {
        FakeNetwork getActiveNetwork(); FakeNetwork[] getAllNetworks();
        FakeCapabilities getNetworkCapabilities(FakeNetwork network);
        FakeLinkProperties getLinkProperties(FakeNetwork network);
        boolean isActiveNetworkMetered(); int getRestrictBackgroundStatus();
        FakeProxyInfo getGlobalProxy(); void registerDefaultNetworkCallback(NetworkCallback callback);
        void unregisterNetworkCallback(NetworkCallback callback); boolean bindProcessToNetwork(FakeNetwork network);
    }
    public static final class ConnectivityDelegate implements ConnectivityApi {
        int calls;
        @Override public FakeNetwork getActiveNetwork(){calls++;return new FakeNetwork(999);}
        @Override public FakeNetwork[] getAllNetworks(){calls++;return new FakeNetwork[]{new FakeNetwork(999)};}
        @Override public FakeCapabilities getNetworkCapabilities(FakeNetwork network){calls++;return new FakeCapabilities();}
        @Override public FakeLinkProperties getLinkProperties(FakeNetwork network){calls++;return new FakeLinkProperties();}
        @Override public boolean isActiveNetworkMetered(){calls++;return false;}
        @Override public int getRestrictBackgroundStatus(){calls++;return 1;}
        @Override public FakeProxyInfo getGlobalProxy(){calls++;return null;}
        @Override public void registerDefaultNetworkCallback(NetworkCallback callback){calls++;}
        @Override public void unregisterNetworkCallback(NetworkCallback callback){calls++;}
        @Override public boolean bindProcessToNetwork(FakeNetwork network){calls++;return true;}
    }
    public static final class FakeNetwork { public int netId; public FakeNetwork(){} public FakeNetwork(int id){netId=id;} public int getNetId(){return netId;} }
    public static final class FakeCapabilities {
        public int downstreamKbps, upstreamKbps; public final java.util.Set<Integer> transports=new java.util.HashSet<>();
        public FakeCapabilities addTransportType(int value){transports.add(value);return this;}
        public FakeCapabilities addCapability(int value){return this;}
        public void setLinkDownstreamBandwidthKbps(int value){downstreamKbps=value;}
        public void setLinkUpstreamBandwidthKbps(int value){upstreamKbps=value;}
    }
    public static final class FakeLinkProperties { public String interfaceName; public int mtu; public List<String> addresses;
        public void setInterfaceName(String value){interfaceName=value;} public void setMtu(int value){mtu=value;}
    }
    public static final class FakeProxyInfo { public String host; public int port; }
    public static class NetworkCallback {
        int available=-1,capabilities=-1; String interfaceName="";
        public void onAvailable(FakeNetwork network){available=network.netId;}
        public void onCapabilitiesChanged(FakeNetwork network,FakeCapabilities value){capabilities=value.downstreamKbps;}
        public void onLinkPropertiesChanged(FakeNetwork network,FakeLinkProperties value){interfaceName=value.interfaceName;}
        public void onBlockedStatusChanged(FakeNetwork network,boolean blocked){}
    }
    public static final class ThrowingNetworkCallback extends NetworkCallback {
        @Override public void onAvailable(FakeNetwork network) { throw new IllegalStateException("callback failure"); }
    }

    public interface DnsApi { void query(String hostname,int type,DnsCallback callback); void rawQuery(String hostname,int type,DnsCallback callback); }
    public static final class DnsDelegate implements DnsApi { int calls; public void query(String h,int t,DnsCallback c){calls++;} public void rawQuery(String h,int t,DnsCallback c){calls++;} }
    public static final class DnsCallback { List<String> values=List.of(); int error=-1; public void onAnswer(List<String> values,int rcode){this.values=values;} public void onError(int code){error=code;} }

    public interface VpnApi { boolean prepareVpn(); String getVpnState(); String getAlwaysOnVpnPackage(); boolean isVpnLockdownEnabled(); List<String> getVpnLockdownAllowlist(); boolean establishVpn(Object token); void stopVpn(Object token); }
    public static final class VpnDelegate implements VpnApi { int calls; public boolean prepareVpn(){calls++;return false;} public String getVpnState(){calls++;return "HOST";} public String getAlwaysOnVpnPackage(){calls++;return "host";} public boolean isVpnLockdownEnabled(){calls++;return false;} public List<String> getVpnLockdownAllowlist(){calls++;return List.of();} public boolean establishVpn(Object token){calls++;return false;} public void stopVpn(Object token){calls++;} }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
