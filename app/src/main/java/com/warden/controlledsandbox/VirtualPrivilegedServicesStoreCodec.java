package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualContextHubProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualContextHubSnapshot;
import com.warden.controlledsandbox.contract.VirtualGraphicsStatsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPersistentDataBlockProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSearchProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualStorageStatsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSystemUpdateProfileSnapshot;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded JSON codec for privileged environment service profiles. */
final class VirtualPrivilegedServicesStoreCodec {
    static final int SCHEMA = 1;
    static final int MAX_SCOPES = 256;
    private VirtualPrivilegedServicesStoreCodec() { }

    static String encode(Map<VirtualSystemServiceStore.Scope,
            VirtualPrivilegedServicesProfileSnapshot> profiles) {
        try {
            JSONArray scopes = new JSONArray();
            for (Map.Entry<VirtualSystemServiceStore.Scope,
                    VirtualPrivilegedServicesProfileSnapshot> entry : profiles.entrySet()) {
                VirtualPrivilegedServicesProfileSnapshot value = entry.getValue();
                scopes.put(new JSONObject()
                        .put("packageName", entry.getKey().packageName())
                        .put("virtualUserId", entry.getKey().virtualUserId())
                        .put("policyVersion", value.policyVersion())
                        .put("updatedAtMs", value.updatedAtMs())
                        .put("search", search(value.search()))
                        .put("storageStats", storage(value.storageStats()))
                        .put("graphicsStats", graphics(value.graphicsStats()))
                        .put("contextHub", contextHub(value.contextHub()))
                        .put("persistentDataBlock", persistent(value.persistentDataBlock()))
                        .put("systemUpdate", update(value.systemUpdate())));
            }
            return new JSONObject().put("schema", SCHEMA).put("scopes", scopes).toString();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode privileged-services profiles", error);
        }
    }

    static Map<VirtualSystemServiceStore.Scope, VirtualPrivilegedServicesProfileSnapshot> decode(
            String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            if (root.getInt("schema") != SCHEMA) {
                throw new IllegalStateException("PRIVILEGED_SERVICES_SCHEMA_UNSUPPORTED");
            }
            JSONArray scopes = root.getJSONArray("scopes");
            if (scopes.length() > MAX_SCOPES) {
                throw new IllegalStateException("Privileged-services scope limit exceeded");
            }
            Map<VirtualSystemServiceStore.Scope, VirtualPrivilegedServicesProfileSnapshot> out =
                    new LinkedHashMap<>();
            for (int index = 0; index < scopes.length(); index++) {
                JSONObject value = scopes.getJSONObject(index);
                VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                        value.getString("packageName"), value.getInt("virtualUserId"));
                VirtualPrivilegedServicesProfileSnapshot previous = out.put(scope,
                        new VirtualPrivilegedServicesProfileSnapshot(
                                value.getLong("policyVersion"), value.optLong("updatedAtMs", 0L),
                                search(value.getJSONObject("search")),
                                storage(value.getJSONObject("storageStats")),
                                graphics(value.getJSONObject("graphicsStats")),
                                contextHub(value.getJSONObject("contextHub")),
                                persistent(value.getJSONObject("persistentDataBlock")),
                                update(value.getJSONObject("systemUpdate"))));
                if (previous != null) throw new IllegalStateException("PRIVILEGED_SERVICES_SCOPE_DUPLICATE");
            }
            return out;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("PRIVILEGED_SERVICES_STORE_CORRUPT", error);
        }
    }

    private static JSONObject search(VirtualSearchProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode())
                .put("globalSearchEnabled", value.globalSearchEnabled())
                .put("webSearchEnabled", value.webSearchEnabled())
                .put("globalSearchComponent", value.globalSearchComponent())
                .put("webSearchComponent", value.webSearchComponent())
                .put("searchableComponents", new JSONArray(value.searchableComponents()))
                .put("suggestionAuthorities", new JSONArray(value.suggestionAuthorities()))
                .put("maximumSuggestionResults", value.maximumSuggestionResults());
    }
    private static VirtualSearchProfileSnapshot search(JSONObject value) throws Exception {
        return new VirtualSearchProfileSnapshot(value.getString("mode"),
                value.optBoolean("globalSearchEnabled", false),
                value.optBoolean("webSearchEnabled", false),
                value.optString("globalSearchComponent", ""),
                value.optString("webSearchComponent", ""),
                strings(value.optJSONArray("searchableComponents"), 128),
                strings(value.optJSONArray("suggestionAuthorities"), 128),
                value.optInt("maximumSuggestionResults", 0));
    }

    private static JSONObject storage(VirtualStorageStatsProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("totalBytes", value.totalBytes())
                .put("freeBytes", value.freeBytes()).put("cacheQuotaBytes", value.cacheQuotaBytes())
                .put("appBytes", value.appBytes()).put("dataBytes", value.dataBytes())
                .put("cacheBytes", value.cacheBytes()).put("externalCacheBytes", value.externalCacheBytes())
                .put("quotaSupported", value.quotaSupported())
                .put("reservedSupported", value.reservedSupported());
    }
    private static VirtualStorageStatsProfileSnapshot storage(JSONObject value) throws Exception {
        return new VirtualStorageStatsProfileSnapshot(value.getString("mode"),
                value.optLong("totalBytes", 0L), value.optLong("freeBytes", 0L),
                value.optLong("cacheQuotaBytes", 0L), value.optLong("appBytes", 0L),
                value.optLong("dataBytes", 0L), value.optLong("cacheBytes", 0L),
                value.optLong("externalCacheBytes", 0L),
                value.optBoolean("quotaSupported", false),
                value.optBoolean("reservedSupported", false));
    }

    private static JSONObject graphics(VirtualGraphicsStatsProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("exposeStats", value.exposeStats())
                .put("allowBufferRequests", value.allowBufferRequests())
                .put("maximumBuffers", value.maximumBuffers()).put("totalFrames", value.totalFrames())
                .put("jankyFrames", value.jankyFrames()).put("lastResetTimeMs", value.lastResetTimeMs());
    }
    private static VirtualGraphicsStatsProfileSnapshot graphics(JSONObject value) throws Exception {
        return new VirtualGraphicsStatsProfileSnapshot(value.getString("mode"),
                value.optBoolean("exposeStats", false),
                value.optBoolean("allowBufferRequests", false),
                value.optInt("maximumBuffers", 0), value.optLong("totalFrames", 0L),
                value.optLong("jankyFrames", 0L), value.optLong("lastResetTimeMs", 0L));
    }

    private static JSONObject contextHub(VirtualContextHubProfileSnapshot value) throws Exception {
        JSONArray hubs = new JSONArray();
        for (VirtualContextHubSnapshot hub : value.hubs()) {
            hubs.put(new JSONObject().put("hubId", hub.hubId()).put("name", hub.name())
                    .put("vendor", hub.vendor())
                    .put("maximumPacketLengthBytes", hub.maximumPacketLengthBytes())
                    .put("nanoAppIds", new JSONArray(hub.nanoAppIds())));
        }
        return new JSONObject().put("mode", value.mode())
                .put("contextHubAvailable", value.contextHubAvailable())
                .put("allowClientSessions", value.allowClientSessions())
                .put("allowMessages", value.allowMessages())
                .put("allowNanoAppMutations", value.allowNanoAppMutations())
                .put("maximumClients", value.maximumClients()).put("hubs", hubs);
    }
    private static VirtualContextHubProfileSnapshot contextHub(JSONObject value) throws Exception {
        JSONArray hubs = value.optJSONArray("hubs");
        ArrayList<VirtualContextHubSnapshot> out = new ArrayList<>();
        if (hubs != null) {
            if (hubs.length() > 32) throw new IllegalStateException("ContextHub list limit exceeded");
            for (int index = 0; index < hubs.length(); index++) {
                JSONObject hub = hubs.getJSONObject(index);
                out.add(new VirtualContextHubSnapshot(hub.getInt("hubId"), hub.getString("name"),
                        hub.optString("vendor", ""), hub.optInt("maximumPacketLengthBytes", 0),
                        strings(hub.optJSONArray("nanoAppIds"), 256)));
            }
        }
        return new VirtualContextHubProfileSnapshot(value.getString("mode"),
                value.optBoolean("contextHubAvailable", false),
                value.optBoolean("allowClientSessions", false),
                value.optBoolean("allowMessages", false),
                value.optBoolean("allowNanoAppMutations", false),
                value.optInt("maximumClients", 0), out);
    }

    private static JSONObject persistent(VirtualPersistentDataBlockProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("readable", value.readable())
                .put("writable", value.writable()).put("allowWipe", value.allowWipe())
                .put("maximumDataBytes", value.maximumDataBytes())
                .put("data", Base64.getEncoder().encodeToString(value.data()))
                .put("oemUnlockEnabled", value.oemUnlockEnabled())
                .put("flashLockState", value.flashLockState())
                .put("checksumValid", value.checksumValid());
    }
    private static VirtualPersistentDataBlockProfileSnapshot persistent(JSONObject value) throws Exception {
        byte[] data = Base64.getDecoder().decode(value.optString("data", ""));
        return new VirtualPersistentDataBlockProfileSnapshot(value.getString("mode"),
                value.optBoolean("readable", false), value.optBoolean("writable", false),
                value.optBoolean("allowWipe", false), value.optInt("maximumDataBytes", 0), data,
                value.optBoolean("oemUnlockEnabled", false), value.optInt("flashLockState", 0),
                value.optBoolean("checksumValid", true));
    }

    private static JSONObject update(VirtualSystemUpdateProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("queryEnabled", value.queryEnabled())
                .put("allowStatusSubmission", value.allowStatusSubmission())
                .put("status", value.status()).put("title", value.title())
                .put("version", value.version()).put("securityPatch", value.securityPatch())
                .put("progressPercent", value.progressPercent())
                .put("receivedTimeMs", value.receivedTimeMs());
    }
    private static VirtualSystemUpdateProfileSnapshot update(JSONObject value) throws Exception {
        return new VirtualSystemUpdateProfileSnapshot(value.getString("mode"),
                value.optBoolean("queryEnabled", true),
                value.optBoolean("allowStatusSubmission", false),
                value.optString("status", "UNKNOWN"), value.optString("title", ""),
                value.optString("version", ""), value.optString("securityPatch", ""),
                value.optInt("progressPercent", 0), value.optLong("receivedTimeMs", 0L));
    }

    private static List<String> strings(JSONArray array, int maximum) throws Exception {
        if (array == null) return List.of();
        if (array.length() > maximum) throw new IllegalStateException("String-list limit exceeded");
        ArrayList<String> out = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) out.add(array.getString(index));
        return out;
    }
}
