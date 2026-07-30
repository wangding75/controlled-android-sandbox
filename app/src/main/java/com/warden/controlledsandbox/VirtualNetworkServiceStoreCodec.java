package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualConnectivityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsRecordSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualProxyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualVpnProfileSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** JSON schema for virtual connectivity/DNS/proxy/VPN profiles. */
final class VirtualNetworkServiceStoreCodec {
    static final int SCHEMA = 1;
    static final int MAX_SCOPES = 512;
    private VirtualNetworkServiceStoreCodec() { }

    static String encode(Map<VirtualSystemServiceStore.Scope, VirtualNetworkServiceProfileSnapshot> profiles) {
        try {
            JSONArray scopes = new JSONArray();
            for (Map.Entry<VirtualSystemServiceStore.Scope, VirtualNetworkServiceProfileSnapshot> entry : profiles.entrySet()) {
                scopes.put(new JSONObject().put("packageName", entry.getKey().packageName())
                        .put("virtualUserId", entry.getKey().virtualUserId())
                        .put("profile", profile(entry.getValue())));
            }
            return new JSONObject().put("schemaVersion", SCHEMA).put("scopes", scopes).toString();
        } catch (Exception error) { throw new IllegalStateException("Cannot encode network profiles", error); }
    }

    static Map<VirtualSystemServiceStore.Scope, VirtualNetworkServiceProfileSnapshot> decode(String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            if (root.optInt("schemaVersion", -1) != SCHEMA) throw new IllegalStateException("Unsupported network-profile schema");
            JSONArray scopes = root.optJSONArray("scopes");
            Map<VirtualSystemServiceStore.Scope, VirtualNetworkServiceProfileSnapshot> result = new LinkedHashMap<>();
            if (scopes == null) return result;
            if (scopes.length() > MAX_SCOPES) throw new IllegalStateException("Network-profile scope limit exceeded");
            for (int index = 0; index < scopes.length(); index++) {
                JSONObject item = scopes.getJSONObject(index);
                VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                        item.getString("packageName"), item.getInt("virtualUserId"));
                if (result.putIfAbsent(scope, profile(item.getJSONObject("profile"))) != null) {
                    throw new IllegalStateException("Duplicate network-profile scope");
                }
            }
            return result;
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Cannot decode network profiles", error); }
    }

    private static JSONObject profile(VirtualNetworkServiceProfileSnapshot value) throws Exception {
        return new JSONObject().put("policyVersion", value.policyVersion()).put("updatedAtMs", value.updatedAtMs())
                .put("connectivity", connectivity(value.connectivity())).put("dns", dns(value.dns()))
                .put("proxy", proxy(value.proxy())).put("vpn", vpn(value.vpn()));
    }
    private static VirtualNetworkServiceProfileSnapshot profile(JSONObject value) throws Exception {
        return new VirtualNetworkServiceProfileSnapshot(value.getLong("policyVersion"), value.optLong("updatedAtMs", 0L),
                connectivity(value.getJSONObject("connectivity")), dns(value.getJSONObject("dns")),
                proxy(value.getJSONObject("proxy")), vpn(value.getJSONObject("vpn")));
    }
    private static JSONObject connectivity(VirtualConnectivityProfileSnapshot value) throws Exception {
        JSONArray networks = new JSONArray();
        for (VirtualNetworkSnapshot network : value.networks()) networks.put(network(network));
        return new JSONObject().put("mode", value.mode()).put("defaultNetworkId", value.defaultNetworkId())
                .put("airplaneMode", value.airplaneMode()).put("backgroundRestricted", value.backgroundRestricted())
                .put("maximumCallbacks", value.maximumCallbacks()).put("networks", networks);
    }
    private static VirtualConnectivityProfileSnapshot connectivity(JSONObject value) throws Exception {
        JSONArray array = value.optJSONArray("networks");
        List<VirtualNetworkSnapshot> networks = new ArrayList<>();
        if (array != null) {
            if (array.length() > 32) throw new IllegalStateException("Network limit exceeded");
            for (int i = 0; i < array.length(); i++) networks.add(network(array.getJSONObject(i)));
        }
        return new VirtualConnectivityProfileSnapshot(value.getString("mode"), value.optInt("defaultNetworkId", -1),
                value.optBoolean("airplaneMode", false), value.optBoolean("backgroundRestricted", false),
                value.optInt("maximumCallbacks", 16), networks);
    }
    private static JSONObject network(VirtualNetworkSnapshot value) throws Exception {
        return new JSONObject().put("networkId", value.networkId()).put("transport", value.transport())
                .put("connected", value.connected()).put("validated", value.validated())
                .put("metered", value.metered()).put("roaming", value.roaming())
                .put("captivePortal", value.captivePortal()).put("interfaceName", value.interfaceName())
                .put("mtu", value.mtu()).put("downstreamKbps", value.downstreamKbps())
                .put("upstreamKbps", value.upstreamKbps()).put("addresses", new JSONArray(value.addresses()))
                .put("dnsServers", new JSONArray(value.dnsServers())).put("routes", new JSONArray(value.routes()))
                .put("domains", value.domains());
    }
    private static VirtualNetworkSnapshot network(JSONObject value) throws Exception {
        return new VirtualNetworkSnapshot(value.getInt("networkId"), value.getString("transport"),
                value.optBoolean("connected", false), value.optBoolean("validated", false),
                value.optBoolean("metered", false), value.optBoolean("roaming", false),
                value.optBoolean("captivePortal", false), value.optString("interfaceName", ""),
                value.optInt("mtu", 0), value.optInt("downstreamKbps", 0), value.optInt("upstreamKbps", 0),
                strings(value.optJSONArray("addresses"), 32), strings(value.optJSONArray("dnsServers"), 16),
                strings(value.optJSONArray("routes"), 64), value.optString("domains", ""));
    }
    private static JSONObject dns(VirtualDnsProfileSnapshot value) throws Exception {
        JSONArray records = new JSONArray();
        for (VirtualDnsRecordSnapshot record : value.records()) records.put(new JSONObject()
                .put("hostname", record.hostname()).put("type", record.type())
                .put("values", new JSONArray(record.values())).put("ttlSeconds", record.ttlSeconds()));
        return new JSONObject().put("mode", value.mode()).put("servers", new JSONArray(value.servers()))
                .put("searchDomains", new JSONArray(value.searchDomains())).put("privateDnsMode", value.privateDnsMode())
                .put("privateDnsHostname", value.privateDnsHostname()).put("allowRawQueries", value.allowRawQueries())
                .put("records", records);
    }
    private static VirtualDnsProfileSnapshot dns(JSONObject value) throws Exception {
        JSONArray array = value.optJSONArray("records");
        List<VirtualDnsRecordSnapshot> records = new ArrayList<>();
        if (array != null) {
            if (array.length() > 256) throw new IllegalStateException("DNS record limit exceeded");
            for (int i = 0; i < array.length(); i++) {
                JSONObject record = array.getJSONObject(i);
                records.add(new VirtualDnsRecordSnapshot(record.getString("hostname"), record.getString("type"),
                        strings(record.getJSONArray("values"), 16), record.optInt("ttlSeconds", 60)));
            }
        }
        return new VirtualDnsProfileSnapshot(value.getString("mode"), strings(value.optJSONArray("servers"), 16),
                strings(value.optJSONArray("searchDomains"), 16), value.optString("privateDnsMode", "OFF"),
                value.optString("privateDnsHostname", ""), value.optBoolean("allowRawQueries", false), records);
    }
    private static JSONObject proxy(VirtualProxyProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("type", value.type()).put("host", value.host())
                .put("port", value.port()).put("exclusionList", new JSONArray(value.exclusionList()))
                .put("pacUrl", value.pacUrl()).put("allowGuestOverride", value.allowGuestOverride());
    }
    private static VirtualProxyProfileSnapshot proxy(JSONObject value) throws Exception {
        return new VirtualProxyProfileSnapshot(value.getString("mode"), value.optString("type", "NONE"),
                value.optString("host", ""), value.optInt("port", 0), strings(value.optJSONArray("exclusionList"), 64),
                value.optString("pacUrl", ""), value.optBoolean("allowGuestOverride", false));
    }
    private static JSONObject vpn(VirtualVpnProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("state", value.state())
                .put("alwaysOnPackage", value.alwaysOnPackage()).put("lockdown", value.lockdown())
                .put("lockdownAllowlist", new JSONArray(value.lockdownAllowlist()))
                .put("allowProvisioning", value.allowProvisioning()).put("allowEstablish", value.allowEstablish())
                .put("maximumSessions", value.maximumSessions()).put("interfaceName", value.interfaceName())
                .put("addresses", new JSONArray(value.addresses())).put("routes", new JSONArray(value.routes()))
                .put("dnsServers", new JSONArray(value.dnsServers()));
    }
    private static VirtualVpnProfileSnapshot vpn(JSONObject value) throws Exception {
        return new VirtualVpnProfileSnapshot(value.getString("mode"), value.optString("state", "DISCONNECTED"),
                value.optString("alwaysOnPackage", ""), value.optBoolean("lockdown", false),
                strings(value.optJSONArray("lockdownAllowlist"), 128), value.optBoolean("allowProvisioning", false),
                value.optBoolean("allowEstablish", false), value.optInt("maximumSessions", 0),
                value.optString("interfaceName", ""), strings(value.optJSONArray("addresses"), 32),
                strings(value.optJSONArray("routes"), 64), strings(value.optJSONArray("dnsServers"), 16));
    }
    private static List<String> strings(JSONArray array, int maximum) throws Exception {
        List<String> values = new ArrayList<>();
        if (array == null) return values;
        if (array.length() > maximum) throw new IllegalStateException("String-list limit exceeded");
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
    }
}
