package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded JSON codec for media/communication/system-environment profiles. */
final class VirtualMediaCommunicationStoreCodec {
    static final int SCHEMA = 1;
    static final int MAX_SCOPES = 256;
    private VirtualMediaCommunicationStoreCodec() { }

    static String encode(Map<VirtualSystemServiceStore.Scope, VirtualMediaCommunicationProfileSnapshot> profiles) {
        try {
            JSONArray scopes = new JSONArray();
            for (Map.Entry<VirtualSystemServiceStore.Scope, VirtualMediaCommunicationProfileSnapshot> entry
                    : profiles.entrySet()) {
                VirtualMediaCommunicationProfileSnapshot value = entry.getValue();
                scopes.put(new JSONObject()
                        .put("packageName", entry.getKey().packageName())
                        .put("virtualUserId", entry.getKey().virtualUserId())
                        .put("policyVersion", value.policyVersion())
                        .put("updatedAtMs", value.updatedAtMs())
                        .put("mediaSession", mediaSession(value.mediaSession()))
                        .put("mediaRouter", mediaRouter(value.mediaRouter()))
                        .put("audioRouting", audio(value.audioRouting()))
                        .put("messaging", messaging(value.messaging()))
                        .put("backup", backup(value.backup()))
                        .put("dropBox", dropBox(value.dropBox())));
            }
            return new JSONObject().put("schema", SCHEMA).put("scopes", scopes).toString();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode media communication profiles", error);
        }
    }

    static Map<VirtualSystemServiceStore.Scope, VirtualMediaCommunicationProfileSnapshot> decode(String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            if (root.getInt("schema") != SCHEMA) throw new IllegalStateException("MEDIA_COMMUNICATION_SCHEMA_UNSUPPORTED");
            JSONArray scopes = root.getJSONArray("scopes");
            if (scopes.length() > MAX_SCOPES) throw new IllegalStateException("Media communication scope limit exceeded");
            Map<VirtualSystemServiceStore.Scope, VirtualMediaCommunicationProfileSnapshot> out = new LinkedHashMap<>();
            for (int index = 0; index < scopes.length(); index++) {
                JSONObject value = scopes.getJSONObject(index);
                VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                        value.getString("packageName"), value.getInt("virtualUserId"));
                VirtualMediaCommunicationProfileSnapshot previous = out.put(scope,
                        new VirtualMediaCommunicationProfileSnapshot(
                                value.getLong("policyVersion"), value.optLong("updatedAtMs", 0L),
                                mediaSession(value.getJSONObject("mediaSession")),
                                mediaRouter(value.getJSONObject("mediaRouter")),
                                audio(value.getJSONObject("audioRouting")),
                                messaging(value.getJSONObject("messaging")),
                                backup(value.getJSONObject("backup")),
                                dropBox(value.getJSONObject("dropBox"))));
                if (previous != null) throw new IllegalStateException("MEDIA_COMMUNICATION_SCOPE_DUPLICATE");
            }
            return out;
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("MEDIA_COMMUNICATION_STORE_CORRUPT", error); }
    }

    private static JSONObject mediaSession(VirtualMediaSessionProfileSnapshot v) throws Exception {
        return new JSONObject().put("mode", v.mode()).put("active", v.active())
                .put("allowSessionCreation", v.allowSessionCreation())
                .put("allowTransportControls", v.allowTransportControls())
                .put("maximumSessions", v.maximumSessions()).put("playbackState", v.playbackState())
                .put("playbackPositionMs", v.playbackPositionMs()).put("title", v.title()).put("artist", v.artist());
    }
    private static VirtualMediaSessionProfileSnapshot mediaSession(JSONObject v) throws Exception {
        return new VirtualMediaSessionProfileSnapshot(v.getString("mode"), v.optBoolean("active", false),
                v.optBoolean("allowSessionCreation", true), v.optBoolean("allowTransportControls", false),
                v.optInt("maximumSessions", 8), v.optString("playbackState", "STOPPED"),
                v.optLong("playbackPositionMs", 0L), v.optString("title", ""), v.optString("artist", ""));
    }
    private static JSONObject mediaRouter(VirtualMediaRouterProfileSnapshot v) throws Exception {
        return new JSONObject().put("mode", v.mode()).put("selectedRouteId", v.selectedRouteId())
                .put("selectedRouteName", v.selectedRouteName()).put("routeType", v.routeType())
                .put("routeVolume", v.routeVolume()).put("routeVolumeMax", v.routeVolumeMax())
                .put("allowRouteChanges", v.allowRouteChanges()).put("maximumClients", v.maximumClients());
    }
    private static VirtualMediaRouterProfileSnapshot mediaRouter(JSONObject v) throws Exception {
        return new VirtualMediaRouterProfileSnapshot(v.getString("mode"), v.optString("selectedRouteId", "local"),
                v.optString("selectedRouteName", "This device"), v.optInt("routeType", 1),
                v.optInt("routeVolume", 5), v.optInt("routeVolumeMax", 15),
                v.optBoolean("allowRouteChanges", false), v.optInt("maximumClients", 8));
    }
    private static JSONObject audio(VirtualAudioRoutingProfileSnapshot v) throws Exception {
        return new JSONObject().put("mode", v.mode()).put("audioMode", v.audioMode()).put("ringerMode", v.ringerMode())
                .put("speakerphoneOn", v.speakerphoneOn()).put("bluetoothScoOn", v.bluetoothScoOn())
                .put("microphoneMuted", v.microphoneMuted()).put("musicVolume", v.musicVolume())
                .put("musicVolumeMax", v.musicVolumeMax()).put("allowVolumeChanges", v.allowVolumeChanges())
                .put("allowAudioFocus", v.allowAudioFocus()).put("maximumFocusOwners", v.maximumFocusOwners());
    }
    private static VirtualAudioRoutingProfileSnapshot audio(JSONObject v) throws Exception {
        return new VirtualAudioRoutingProfileSnapshot(v.getString("mode"), v.optInt("audioMode", 0),
                v.optInt("ringerMode", 2), v.optBoolean("speakerphoneOn", false),
                v.optBoolean("bluetoothScoOn", false), v.optBoolean("microphoneMuted", false),
                v.optInt("musicVolume", 5), v.optInt("musicVolumeMax", 15),
                v.optBoolean("allowVolumeChanges", true), v.optBoolean("allowAudioFocus", true),
                v.optInt("maximumFocusOwners", 8));
    }
    private static JSONObject messaging(VirtualMessagingProfileSnapshot v) throws Exception {
        return new JSONObject().put("mode", v.mode()).put("subscriptionId", v.subscriptionId())
                .put("defaultSmsPackage", v.defaultSmsPackage()).put("allowTextMessages", v.allowTextMessages())
                .put("allowDataMessages", v.allowDataMessages()).put("allowMultipartMessages", v.allowMultipartMessages())
                .put("maximumMessagesPerWindow", v.maximumMessagesPerWindow()).put("quotaWindowMs", v.quotaWindowMs())
                .put("storeSentMessages", v.storeSentMessages());
    }
    private static VirtualMessagingProfileSnapshot messaging(JSONObject v) throws Exception {
        return new VirtualMessagingProfileSnapshot(v.getString("mode"), v.optInt("subscriptionId", -1),
                v.optString("defaultSmsPackage", ""), v.optBoolean("allowTextMessages", false),
                v.optBoolean("allowDataMessages", false), v.optBoolean("allowMultipartMessages", false),
                v.optInt("maximumMessagesPerWindow", 0), v.optLong("quotaWindowMs", 60000L),
                v.optBoolean("storeSentMessages", false));
    }
    private static JSONObject backup(VirtualBackupProfileSnapshot v) throws Exception {
        return new JSONObject().put("mode", v.mode()).put("backupEnabled", v.backupEnabled())
                .put("backupProvisioned", v.backupProvisioned()).put("currentTransport", v.currentTransport())
                .put("transports", new JSONArray(v.transports())).put("allowDataChanged", v.allowDataChanged())
                .put("allowBackupNow", v.allowBackupNow()).put("allowRestore", v.allowRestore());
    }
    private static VirtualBackupProfileSnapshot backup(JSONObject v) throws Exception {
        return new VirtualBackupProfileSnapshot(v.getString("mode"), v.optBoolean("backupEnabled", false),
                v.optBoolean("backupProvisioned", false), v.optString("currentTransport", ""),
                strings(v.optJSONArray("transports"), 128), v.optBoolean("allowDataChanged", true),
                v.optBoolean("allowBackupNow", false), v.optBoolean("allowRestore", false));
    }
    private static JSONObject dropBox(VirtualDropBoxProfileSnapshot v) throws Exception {
        return new JSONObject().put("mode", v.mode()).put("enabledTags", new JSONArray(v.enabledTags()))
                .put("allowWrites", v.allowWrites()).put("exposeEntries", v.exposeEntries())
                .put("maximumEntries", v.maximumEntries()).put("maximumEntryBytes", v.maximumEntryBytes());
    }
    private static VirtualDropBoxProfileSnapshot dropBox(JSONObject v) throws Exception {
        return new VirtualDropBoxProfileSnapshot(v.getString("mode"), strings(v.optJSONArray("enabledTags"), 128),
                v.optBoolean("allowWrites", false), v.optBoolean("exposeEntries", false),
                v.optInt("maximumEntries", 0), v.optInt("maximumEntryBytes", 0));
    }
    private static List<String> strings(JSONArray array, int max) throws Exception {
        if (array == null) return List.of();
        if (array.length() > max) throw new IllegalStateException("String-list limit exceeded");
        ArrayList<String> out = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) out.add(array.getString(index));
        return out;
    }
}
