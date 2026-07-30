package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualDisplayProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplaySnapshot;
import com.warden.controlledsandbox.contract.VirtualInputMethodProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** JSON schema for virtual window/input/display profiles. */
final class VirtualInteractionStoreCodec {
    static final int SCHEMA = 1;
    static final int MAX_SCOPES = 512;
    private VirtualInteractionStoreCodec() { }

    static String encode(Map<VirtualSystemServiceStore.Scope, VirtualInteractionProfileSnapshot> profiles) {
        try {
            JSONArray scopes = new JSONArray();
            for (Map.Entry<VirtualSystemServiceStore.Scope, VirtualInteractionProfileSnapshot> entry
                    : profiles.entrySet()) {
                scopes.put(new JSONObject().put("packageName", entry.getKey().packageName())
                        .put("virtualUserId", entry.getKey().virtualUserId())
                        .put("profile", profile(entry.getValue())));
            }
            return new JSONObject().put("schemaVersion", SCHEMA).put("scopes", scopes).toString();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode interaction profiles", error);
        }
    }

    static Map<VirtualSystemServiceStore.Scope, VirtualInteractionProfileSnapshot> decode(String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            if (root.optInt("schemaVersion", -1) != SCHEMA) {
                throw new IllegalStateException("Unsupported interaction-profile schema");
            }
            JSONArray scopes = root.optJSONArray("scopes");
            Map<VirtualSystemServiceStore.Scope, VirtualInteractionProfileSnapshot> result = new LinkedHashMap<>();
            if (scopes == null) return result;
            if (scopes.length() > MAX_SCOPES) throw new IllegalStateException("Interaction-profile scope limit exceeded");
            for (int index = 0; index < scopes.length(); index++) {
                JSONObject item = scopes.getJSONObject(index);
                VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                        item.getString("packageName"), item.getInt("virtualUserId"));
                if (result.putIfAbsent(scope, profile(item.getJSONObject("profile"))) != null) {
                    throw new IllegalStateException("Duplicate interaction-profile scope");
                }
            }
            return result;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot decode interaction profiles", error);
        }
    }

    private static JSONObject profile(VirtualInteractionProfileSnapshot value) throws Exception {
        return new JSONObject().put("policyVersion", value.policyVersion())
                .put("updatedAtMs", value.updatedAtMs())
                .put("window", window(value.window()))
                .put("inputMethod", input(value.inputMethod()))
                .put("display", display(value.display()));
    }
    private static VirtualInteractionProfileSnapshot profile(JSONObject value) throws Exception {
        return new VirtualInteractionProfileSnapshot(value.getLong("policyVersion"),
                value.optLong("updatedAtMs", 0L), window(value.getJSONObject("window")),
                input(value.getJSONObject("inputMethod")), display(value.getJSONObject("display")));
    }
    private static JSONObject window(VirtualWindowPolicySnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("maximumWindows", value.maximumWindows())
                .put("rewritePackageName", value.rewritePackageName())
                .put("allowSecureFlag", value.allowSecureFlag())
                .put("allowSystemAlertWindows", value.allowSystemAlertWindows())
                .put("allowScreenCaptureControl", value.allowScreenCaptureControl());
    }
    private static VirtualWindowPolicySnapshot window(JSONObject value) throws Exception {
        return new VirtualWindowPolicySnapshot(value.getString("mode"),
                value.optInt("maximumWindows", 32), value.optBoolean("rewritePackageName", true),
                value.optBoolean("allowSecureFlag", true),
                value.optBoolean("allowSystemAlertWindows", false),
                value.optBoolean("allowScreenCaptureControl", false));
    }
    private static JSONObject input(VirtualInputMethodProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode())
                .put("selectedInputMethodId", value.selectedInputMethodId())
                .put("enabledInputMethodIds", new JSONArray(value.enabledInputMethodIds()))
                .put("allowPicker", value.allowPicker())
                .put("allowInlineSuggestions", value.allowInlineSuggestions())
                .put("allowFullscreen", value.allowFullscreen())
                .put("showSoftInputOnFocus", value.showSoftInputOnFocus())
                .put("maximumSessions", value.maximumSessions());
    }
    private static VirtualInputMethodProfileSnapshot input(JSONObject value) throws Exception {
        JSONArray array = value.optJSONArray("enabledInputMethodIds");
        List<String> ids = new ArrayList<>();
        if (array != null) {
            if (array.length() > 64) throw new IllegalStateException("Input-method ID limit exceeded");
            for (int index = 0; index < array.length(); index++) ids.add(array.getString(index));
        }
        return new VirtualInputMethodProfileSnapshot(value.getString("mode"),
                value.optString("selectedInputMethodId", ""), ids,
                value.optBoolean("allowPicker", false),
                value.optBoolean("allowInlineSuggestions", false),
                value.optBoolean("allowFullscreen", false),
                value.optBoolean("showSoftInputOnFocus", true),
                value.optInt("maximumSessions", 8));
    }
    private static JSONObject display(VirtualDisplayProfileSnapshot value) throws Exception {
        JSONArray displays = new JSONArray();
        for (VirtualDisplaySnapshot item : value.displays()) displays.put(new JSONObject()
                .put("displayId", item.displayId()).put("name", item.name())
                .put("widthPixels", item.widthPixels()).put("heightPixels", item.heightPixels())
                .put("densityDpi", item.densityDpi()).put("xdpi", item.xdpi()).put("ydpi", item.ydpi())
                .put("refreshRate", item.refreshRate()).put("rotation", item.rotation())
                .put("state", item.state()).put("flags", item.flags()).put("secure", item.secure()));
        return new JSONObject().put("mode", value.mode()).put("defaultDisplayId", value.defaultDisplayId())
                .put("allowCreateVirtualDisplay", value.allowCreateVirtualDisplay())
                .put("maximumVirtualDisplays", value.maximumVirtualDisplays()).put("displays", displays);
    }
    private static VirtualDisplayProfileSnapshot display(JSONObject value) throws Exception {
        JSONArray array = value.getJSONArray("displays");
        List<VirtualDisplaySnapshot> displays = new ArrayList<>();
        if (array.length() > 16) throw new IllegalStateException("Display limit exceeded");
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            displays.add(new VirtualDisplaySnapshot(item.getInt("displayId"), item.getString("name"),
                    item.getInt("widthPixels"), item.getInt("heightPixels"), item.getInt("densityDpi"),
                    (float) item.optDouble("xdpi", item.getInt("densityDpi")),
                    (float) item.optDouble("ydpi", item.getInt("densityDpi")),
                    (float) item.optDouble("refreshRate", 60d), item.optInt("rotation", 0),
                    item.optInt("state", 2), item.optInt("flags", 0), item.optBoolean("secure", false)));
        }
        return new VirtualDisplayProfileSnapshot(value.getString("mode"),
                value.optInt("defaultDisplayId", 0), value.optBoolean("allowCreateVirtualDisplay", false),
                value.optInt("maximumVirtualDisplays", 0), displays);
    }
}
