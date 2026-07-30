package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;
import com.warden.controlledsandbox.contract.VirtualLauncherProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageStatsPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualUserProfileSnapshot;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** JSON schema for application-environment profiles and bounded runtime data. */
final class ApplicationEnvironmentStoreCodec {
    static final int SCHEMA = 1;
    static final int MAX_SCOPES = 512;
    private ApplicationEnvironmentStoreCodec() { }

    static String encode(Map<VirtualSystemServiceStore.Scope, ApplicationEnvironmentStore.ScopeData> scopes) {
        try {
            JSONArray values = new JSONArray();
            for (Map.Entry<VirtualSystemServiceStore.Scope, ApplicationEnvironmentStore.ScopeData> entry
                    : scopes.entrySet()) {
                ApplicationEnvironmentStore.ScopeData data = entry.getValue();
                JSONArray shortcuts = new JSONArray();
                for (VirtualShortcutSnapshot value : data.shortcuts.values()) shortcuts.put(shortcut(value));
                JSONArray widgets = new JSONArray();
                for (VirtualWidgetSnapshot value : data.widgets.values()) widgets.put(widget(value));
                JSONArray usage = new JSONArray();
                for (VirtualUsageEventSnapshot value : data.usageEvents) usage.put(usage(value));
                JSONArray settings = new JSONArray();
                for (VirtualSettingSnapshot value : data.settings.values()) settings.put(setting(value));
                values.put(new JSONObject().put("packageName", entry.getKey().packageName())
                        .put("virtualUserId", entry.getKey().virtualUserId())
                        .put("profile", profile(data.profile)).put("nextWidgetId", data.nextWidgetId)
                        .put("shortcuts", shortcuts).put("widgets", widgets)
                        .put("usageEvents", usage).put("settings", settings));
            }
            return new JSONObject().put("schemaVersion", SCHEMA).put("scopes", values).toString();
        } catch (Exception error) { throw new IllegalStateException("Cannot encode application environment", error); }
    }

    static Map<VirtualSystemServiceStore.Scope, ApplicationEnvironmentStore.ScopeData> decode(String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            if (root.optInt("schemaVersion", -1) != SCHEMA) {
                throw new IllegalStateException("Unsupported application-environment schema");
            }
            JSONArray values = root.optJSONArray("scopes");
            Map<VirtualSystemServiceStore.Scope, ApplicationEnvironmentStore.ScopeData> result = new LinkedHashMap<>();
            if (values == null) return result;
            if (values.length() > MAX_SCOPES) throw new IllegalStateException("Application-environment scope limit exceeded");
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.getJSONObject(i);
                VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                        item.getString("packageName"), item.getInt("virtualUserId"));
                ApplicationEnvironmentStore.ScopeData data = new ApplicationEnvironmentStore.ScopeData(
                        profile(item.getJSONObject("profile")), item.optInt("nextWidgetId", 10_000));
                JSONArray shortcuts = item.optJSONArray("shortcuts");
                if (shortcuts != null) {
                    if (shortcuts.length() > 256) throw new IllegalStateException("Shortcut limit exceeded");
                    for (int j = 0; j < shortcuts.length(); j++) {
                        VirtualShortcutSnapshot value = shortcut(shortcuts.getJSONObject(j));
                        if (data.shortcuts.put(value.id(), value) != null) throw new IllegalStateException("Duplicate shortcut");
                    }
                }
                JSONArray widgets = item.optJSONArray("widgets");
                if (widgets != null) {
                    if (widgets.length() > 512) throw new IllegalStateException("Widget limit exceeded");
                    for (int j = 0; j < widgets.length(); j++) {
                        VirtualWidgetSnapshot value = widget(widgets.getJSONObject(j));
                        if (data.widgets.put(value.appWidgetId(), value) != null) throw new IllegalStateException("Duplicate widget");
                    }
                }
                JSONArray usage = item.optJSONArray("usageEvents");
                if (usage != null) {
                    if (usage.length() > 100_000) throw new IllegalStateException("Usage-event limit exceeded");
                    for (int j = 0; j < usage.length(); j++) data.usageEvents.add(usage(usage.getJSONObject(j)));
                }
                JSONArray settings = item.optJSONArray("settings");
                if (settings != null) {
                    if (settings.length() > 4096) throw new IllegalStateException("Settings limit exceeded");
                    for (int j = 0; j < settings.length(); j++) {
                        VirtualSettingSnapshot value = setting(settings.getJSONObject(j));
                        if (data.settings.put(value.storageKey(), value) != null) throw new IllegalStateException("Duplicate setting");
                    }
                }
                if (result.putIfAbsent(scope, data) != null) throw new IllegalStateException("Duplicate application scope");
            }
            return result;
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Cannot decode application environment", error); }
    }

    private static JSONObject profile(ApplicationEnvironmentProfileSnapshot value) throws Exception {
        VirtualUserProfileSnapshot user = value.user();
        VirtualLauncherProfileSnapshot launcher = value.launcher();
        VirtualShortcutPolicySnapshot shortcut = value.shortcut();
        VirtualWidgetPolicySnapshot widget = value.appWidget();
        VirtualUsageStatsPolicySnapshot usage = value.usageStats();
        VirtualSettingsProfileSnapshot settings = value.settings();
        return new JSONObject().put("policyVersion", value.policyVersion()).put("updatedAtMs", value.updatedAtMs())
                .put("user", new JSONObject().put("mode", user.mode()).put("userId", user.userId())
                        .put("serialNumber", user.serialNumber()).put("name", user.name()).put("flags", user.flags())
                        .put("running", user.running()).put("unlocked", user.unlocked()).put("quietMode", user.quietMode())
                        .put("restrictions", new JSONArray(user.restrictions()))
                        .put("applicationRestrictionKeys", new JSONArray(user.applicationRestrictionKeys()))
                        .put("applicationRestrictionValues", new JSONArray(user.applicationRestrictionValues())))
                .put("launcher", new JSONObject().put("mode", launcher.mode()).put("enabled", launcher.enabled())
                        .put("allowStartMainActivity", launcher.allowStartMainActivity())
                        .put("allowPackageCallbacks", launcher.allowPackageCallbacks())
                        .put("maximumListeners", launcher.maximumListeners())
                        .put("visiblePackages", new JSONArray(launcher.visiblePackages()))
                        .put("hiddenPackages", new JSONArray(launcher.hiddenPackages())))
                .put("shortcut", new JSONObject().put("mode", shortcut.mode()).put("enabled", shortcut.enabled())
                        .put("maximumShortcutsPerActivity", shortcut.maximumShortcutsPerActivity())
                        .put("maximumDynamicShortcuts", shortcut.maximumDynamicShortcuts())
                        .put("remainingCallCount", shortcut.remainingCallCount())
                        .put("rateLimitResetTimeMs", shortcut.rateLimitResetTimeMs())
                        .put("allowPinRequests", shortcut.allowPinRequests()).put("allowLongLived", shortcut.allowLongLived()))
                .put("appWidget", new JSONObject().put("mode", widget.mode()).put("enabled", widget.enabled())
                        .put("allowBind", widget.allowBind()).put("exposeInstalledProviders", widget.exposeInstalledProviders())
                        .put("maximumWidgets", widget.maximumWidgets()).put("maximumHosts", widget.maximumHosts()))
                .put("usageStats", new JSONObject().put("mode", usage.mode()).put("enabled", usage.enabled())
                        .put("retentionMs", usage.retentionMs()).put("maximumEvents", usage.maximumEvents())
                        .put("allowReportEvents", usage.allowReportEvents())
                        .put("includeOtherPackages", usage.includeOtherPackages()))
                .put("settings", new JSONObject().put("mode", settings.mode())
                        .put("allowSecureWrites", settings.allowSecureWrites())
                        .put("allowSystemWrites", settings.allowSystemWrites())
                        .put("allowGlobalWrites", settings.allowGlobalWrites())
                        .put("maximumEntries", settings.maximumEntries())
                        .put("allowedNamespaces", new JSONArray(settings.allowedNamespaces()))
                        .put("blockedKeys", new JSONArray(settings.blockedKeys())));
    }
    private static ApplicationEnvironmentProfileSnapshot profile(JSONObject value) throws Exception {
        JSONObject user = value.getJSONObject("user"); JSONObject launcher = value.getJSONObject("launcher");
        JSONObject shortcut = value.getJSONObject("shortcut"); JSONObject widget = value.getJSONObject("appWidget");
        JSONObject usage = value.getJSONObject("usageStats"); JSONObject settings = value.getJSONObject("settings");
        return new ApplicationEnvironmentProfileSnapshot(value.getLong("policyVersion"),
                value.optLong("updatedAtMs", 0L),
                new VirtualUserProfileSnapshot(user.getString("mode"), user.getInt("userId"),
                        user.getLong("serialNumber"), user.getString("name"), user.optInt("flags", 0),
                        user.optBoolean("running", true), user.optBoolean("unlocked", true),
                        user.optBoolean("quietMode", false), strings(user.optJSONArray("restrictions"), 128),
                        strings(user.optJSONArray("applicationRestrictionKeys"), 128),
                        strings(user.optJSONArray("applicationRestrictionValues"), 128)),
                new VirtualLauncherProfileSnapshot(launcher.getString("mode"), launcher.optBoolean("enabled", true),
                        launcher.optBoolean("allowStartMainActivity", true),
                        launcher.optBoolean("allowPackageCallbacks", true), launcher.optInt("maximumListeners", 16),
                        strings(launcher.optJSONArray("visiblePackages"), 256),
                        strings(launcher.optJSONArray("hiddenPackages"), 256)),
                new VirtualShortcutPolicySnapshot(shortcut.getString("mode"), shortcut.optBoolean("enabled", true),
                        shortcut.optInt("maximumShortcutsPerActivity", 15),
                        shortcut.optInt("maximumDynamicShortcuts", 64), shortcut.optInt("remainingCallCount", 100),
                        shortcut.optLong("rateLimitResetTimeMs", 0L), shortcut.optBoolean("allowPinRequests", false),
                        shortcut.optBoolean("allowLongLived", true)),
                new VirtualWidgetPolicySnapshot(widget.getString("mode"), widget.optBoolean("enabled", true),
                        widget.optBoolean("allowBind", false), widget.optBoolean("exposeInstalledProviders", false),
                        widget.optInt("maximumWidgets", 32), widget.optInt("maximumHosts", 8)),
                new VirtualUsageStatsPolicySnapshot(usage.getString("mode"), usage.optBoolean("enabled", true),
                        usage.optLong("retentionMs", 604_800_000L), usage.optInt("maximumEvents", 4096),
                        usage.optBoolean("allowReportEvents", true), usage.optBoolean("includeOtherPackages", false)),
                new VirtualSettingsProfileSnapshot(settings.getString("mode"),
                        settings.optBoolean("allowSecureWrites", true), settings.optBoolean("allowSystemWrites", true),
                        settings.optBoolean("allowGlobalWrites", false), settings.optInt("maximumEntries", 512),
                        strings(settings.optJSONArray("allowedNamespaces"), 3),
                        strings(settings.optJSONArray("blockedKeys"), 512)));
    }
    private static JSONObject shortcut(VirtualShortcutSnapshot value) throws Exception {
        return new JSONObject().put("id", value.id()).put("activityClass", value.activityClass())
                .put("shortLabel", value.shortLabel()).put("longLabel", value.longLabel())
                .put("disabledMessage", value.disabledMessage()).put("intentUris", new JSONArray(value.intentUris()))
                .put("rank", value.rank()).put("enabled", value.enabled()).put("dynamic", value.dynamic())
                .put("pinned", value.pinned()).put("manifest", value.manifest()).put("longLived", value.longLived())
                .put("lastChangedMs", value.lastChangedMs()).put("usageCount", value.usageCount());
    }
    private static VirtualShortcutSnapshot shortcut(JSONObject value) throws Exception {
        return new VirtualShortcutSnapshot(value.getString("id"), value.optString("activityClass", ""),
                value.getString("shortLabel"), value.optString("longLabel", ""),
                value.optString("disabledMessage", ""), strings(value.optJSONArray("intentUris"), 16),
                value.optInt("rank", 0), value.optBoolean("enabled", true), value.optBoolean("dynamic", true),
                value.optBoolean("pinned", false), value.optBoolean("manifest", false),
                value.optBoolean("longLived", false), value.optLong("lastChangedMs", 0L),
                value.optInt("usageCount", 0));
    }
    private static JSONObject widget(VirtualWidgetSnapshot value) throws Exception {
        return new JSONObject().put("appWidgetId", value.appWidgetId()).put("hostId", value.hostId())
                .put("providerPackage", value.providerPackage()).put("providerClass", value.providerClass())
                .put("bound", value.bound()).put("optionKeys", new JSONArray(value.optionKeys()))
                .put("optionValues", new JSONArray(value.optionValues()))
                .put("remoteViewsPayload", Base64.getEncoder().encodeToString(value.remoteViewsPayload()))
                .put("updatedAtMs", value.updatedAtMs());
    }
    private static VirtualWidgetSnapshot widget(JSONObject value) throws Exception {
        byte[] payload = Base64.getDecoder().decode(value.optString("remoteViewsPayload", ""));
        return new VirtualWidgetSnapshot(value.getInt("appWidgetId"), value.getInt("hostId"),
                value.optString("providerPackage", ""), value.optString("providerClass", ""),
                value.optBoolean("bound", false), strings(value.optJSONArray("optionKeys"), 64),
                strings(value.optJSONArray("optionValues"), 64), payload, value.optLong("updatedAtMs", 0L));
    }
    private static JSONObject usage(VirtualUsageEventSnapshot value) throws Exception {
        return new JSONObject().put("timestampMs", value.timestampMs()).put("eventType", value.eventType())
                .put("packageName", value.packageName()).put("className", value.className())
                .put("taskRootPackage", value.taskRootPackage()).put("configuration", value.configuration())
                .put("shortcutId", value.shortcutId()).put("instanceId", value.instanceId());
    }
    private static VirtualUsageEventSnapshot usage(JSONObject value) throws Exception {
        return new VirtualUsageEventSnapshot(value.getLong("timestampMs"), value.getInt("eventType"),
                value.getString("packageName"), value.optString("className", ""),
                value.optString("taskRootPackage", ""), value.optString("configuration", ""),
                value.optString("shortcutId", ""), value.optInt("instanceId", 0));
    }
    private static JSONObject setting(VirtualSettingSnapshot value) throws Exception {
        return new JSONObject().put("namespace", value.namespace()).put("key", value.key())
                .put("value", value.value()).put("updatedAtMs", value.updatedAtMs());
    }
    private static VirtualSettingSnapshot setting(JSONObject value) throws Exception {
        return new VirtualSettingSnapshot(value.getString("namespace"), value.getString("key"),
                value.optString("value", ""), value.optLong("updatedAtMs", 0L));
    }
    private static List<String> strings(JSONArray array, int maximum) throws Exception {
        List<String> values = new ArrayList<>(); if (array == null) return values;
        if (array.length() > maximum) throw new IllegalStateException("String-list limit exceeded");
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i)); return values;
    }
}
