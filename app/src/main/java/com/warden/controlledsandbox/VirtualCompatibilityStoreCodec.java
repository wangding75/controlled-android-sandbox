package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualGoogleServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded JSON codec for compatibility profiles. */
final class VirtualCompatibilityStoreCodec {
    static final int SCHEMA = 1;
    static final int MAX_SCOPES = 256;

    private VirtualCompatibilityStoreCodec() { }

    static String encode(
            Map<VirtualSystemServiceStore.Scope, VirtualCompatibilityProfileSnapshot> profiles) {
        try {
            JSONArray scopes = new JSONArray();
            for (Map.Entry<VirtualSystemServiceStore.Scope, VirtualCompatibilityProfileSnapshot>
                    entry : profiles.entrySet()) {
                VirtualCompatibilityProfileSnapshot profile = entry.getValue();
                scopes.put(new JSONObject()
                        .put("packageName", entry.getKey().packageName())
                        .put("virtualUserId", entry.getKey().virtualUserId())
                        .put("policyVersion", profile.policyVersion())
                        .put("updatedAtMs", profile.updatedAtMs())
                        .put("webView", web(profile.webView()))
                        .put("google", google(profile.googleServices()))
                        .put("oem", oem(profile.oem()))
                        .put("detection", detection(profile.detection())));
            }
            return new JSONObject()
                    .put("schema", SCHEMA)
                    .put("scopes", scopes)
                    .toString();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode compatibility profiles", error);
        }
    }

    static Map<VirtualSystemServiceStore.Scope, VirtualCompatibilityProfileSnapshot> decode(
            String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            if (root.getInt("schema") != SCHEMA) {
                throw new IllegalStateException("COMPATIBILITY_SCHEMA_UNSUPPORTED");
            }
            JSONArray array = root.getJSONArray("scopes");
            if (array.length() > MAX_SCOPES) {
                throw new IllegalStateException("Compatibility scope limit exceeded");
            }
            Map<VirtualSystemServiceStore.Scope, VirtualCompatibilityProfileSnapshot> decoded =
                    new LinkedHashMap<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject value = array.getJSONObject(index);
                VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                        value.getString("packageName"), value.getInt("virtualUserId"));
                VirtualCompatibilityProfileSnapshot previous = decoded.put(
                        scope,
                        new VirtualCompatibilityProfileSnapshot(
                                value.getLong("policyVersion"),
                                value.optLong("updatedAtMs", 0L),
                                web(value.getJSONObject("webView")),
                                google(value.getJSONObject("google")),
                                oem(value.getJSONObject("oem")),
                                detection(value.getJSONObject("detection"))));
                if (previous != null) {
                    throw new IllegalStateException("COMPATIBILITY_SCOPE_DUPLICATE");
                }
            }
            return decoded;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("COMPATIBILITY_STORE_CORRUPT", error);
        }
    }

    private static JSONObject web(VirtualWebViewProfileSnapshot value) throws Exception {
        return new JSONObject()
                .put("mode", value.mode())
                .put("providerPackage", value.providerPackage())
                .put("providerVersion", value.providerVersion())
                .put("dataDirectorySuffix", value.dataDirectorySuffix())
                .put("rendererProcessPrefix", value.rendererProcessPrefix())
                .put("multiprocessEnabled", value.multiprocessEnabled())
                .put("safeBrowsingEnabled", value.safeBrowsingEnabled())
                .put("debuggingAllowed", value.debuggingAllowed())
                .put("maximumRendererProcesses", value.maximumRendererProcesses());
    }

    private static VirtualWebViewProfileSnapshot web(JSONObject value) throws Exception {
        return new VirtualWebViewProfileSnapshot(
                value.getString("mode"),
                value.optString("providerPackage", ""),
                value.optString("providerVersion", ""),
                value.optString("dataDirectorySuffix", ""),
                value.optString("rendererProcessPrefix", ""),
                value.optBoolean("multiprocessEnabled", false),
                value.optBoolean("safeBrowsingEnabled", true),
                value.optBoolean("debuggingAllowed", false),
                value.optInt("maximumRendererProcesses", 0));
    }

    private static JSONObject google(VirtualGoogleServicesProfileSnapshot value) throws Exception {
        return new JSONObject()
                .put("mode", value.mode())
                .put("playServicesAvailable", value.playServicesAvailable())
                .put("advertisingId", value.advertisingId())
                .put("limitAdTracking", value.limitAdTracking())
                .put("appSetId", value.appSetId())
                .put("gsfId", value.gsfId())
                .put("installationId", value.installationId())
                .put("visibleAccountTypes", new JSONArray(value.visibleAccountTypes()))
                .put("enabledApis", new JSONArray(value.enabledApis()));
    }

    private static VirtualGoogleServicesProfileSnapshot google(JSONObject value) throws Exception {
        return new VirtualGoogleServicesProfileSnapshot(
                value.getString("mode"),
                value.optBoolean("playServicesAvailable", false),
                value.optString("advertisingId", ""),
                value.optBoolean("limitAdTracking", true),
                value.optString("appSetId", ""),
                value.optString("gsfId", ""),
                value.optString("installationId", ""),
                strings(value.optJSONArray("visibleAccountTypes"), 32),
                strings(value.optJSONArray("enabledApis"), 64));
    }

    private static JSONObject oem(VirtualOemProfileSnapshot value) throws Exception {
        return new JSONObject()
                .put("mode", value.mode())
                .put("vendor", value.vendor())
                .put("skin", value.skin())
                .put("attributionId", value.attributionId())
                .put("propertyKeys", new JSONArray(value.propertyKeys()))
                .put("propertyValues", new JSONArray(value.propertyValues()))
                .put("availableServices", new JSONArray(value.availableServices()))
                .put("blockedPackages", new JSONArray(value.blockedPackages()));
    }

    private static VirtualOemProfileSnapshot oem(JSONObject value) throws Exception {
        return new VirtualOemProfileSnapshot(
                value.getString("mode"),
                value.optString("vendor", ""),
                value.optString("skin", ""),
                value.optString("attributionId", ""),
                strings(value.optJSONArray("propertyKeys"), 128),
                strings(value.optJSONArray("propertyValues"), 128),
                strings(value.optJSONArray("availableServices"), 64),
                strings(value.optJSONArray("blockedPackages"), 128));
    }

    private static JSONObject detection(VirtualDetectionPolicySnapshot value) throws Exception {
        return new JSONObject()
                .put("mode", value.mode())
                .put("hideHostPackage", value.hideHostPackage())
                .put("sanitizeProcFiles", value.sanitizeProcFiles())
                .put("maskDebugger", value.maskDebugger())
                .put("maskRootArtifacts", value.maskRootArtifacts())
                .put("sanitizeStackTraces", value.sanitizeStackTraces())
                .put("maximumSuspiciousQueries", value.maximumSuspiciousQueries())
                .put("hiddenPathPrefixes", new JSONArray(value.hiddenPathPrefixes()))
                .put("hiddenClassPrefixes", new JSONArray(value.hiddenClassPrefixes()))
                .put("hiddenPackageNames", new JSONArray(value.hiddenPackageNames()));
    }

    private static VirtualDetectionPolicySnapshot detection(JSONObject value) throws Exception {
        return new VirtualDetectionPolicySnapshot(
                value.getString("mode"),
                value.optBoolean("hideHostPackage", true),
                value.optBoolean("sanitizeProcFiles", true),
                value.optBoolean("maskDebugger", true),
                value.optBoolean("maskRootArtifacts", true),
                value.optBoolean("sanitizeStackTraces", true),
                value.optInt("maximumSuspiciousQueries", 4096),
                strings(value.optJSONArray("hiddenPathPrefixes"), 128),
                strings(value.optJSONArray("hiddenClassPrefixes"), 128),
                strings(value.optJSONArray("hiddenPackageNames"), 128));
    }

    private static List<String> strings(JSONArray array, int maximum) throws Exception {
        if (array == null) {
            return List.of();
        }
        if (array.length() > maximum) {
            throw new IllegalStateException("String-list limit exceeded");
        }
        List<String> values = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            values.add(array.getString(index));
        }
        return values;
    }
}
