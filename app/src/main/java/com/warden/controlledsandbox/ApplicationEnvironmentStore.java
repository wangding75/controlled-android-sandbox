package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Package-Service-owned durable application-environment profile and bounded runtime data. */
final class ApplicationEnvironmentStore {
    static final class ScopeData {
        ApplicationEnvironmentProfileSnapshot profile;
        int nextWidgetId;
        final Map<String, VirtualShortcutSnapshot> shortcuts = new LinkedHashMap<>();
        final Map<Integer, VirtualWidgetSnapshot> widgets = new LinkedHashMap<>();
        final List<VirtualUsageEventSnapshot> usageEvents = new ArrayList<>();
        final Map<String, VirtualSettingSnapshot> settings = new LinkedHashMap<>();
        ScopeData(ApplicationEnvironmentProfileSnapshot profile, int nextWidgetId) {
            this.profile = java.util.Objects.requireNonNull(profile, "profile");
            this.nextWidgetId = Math.max(1, nextWidgetId);
        }
        ScopeData copy() {
            ScopeData out = new ScopeData(profile, nextWidgetId);
            out.shortcuts.putAll(shortcuts); out.widgets.putAll(widgets);
            out.usageEvents.addAll(usageEvents); out.settings.putAll(settings); return out;
        }
    }
    private final ApplicationEnvironmentStorePersistence persistence;
    private final Map<VirtualSystemServiceStore.Scope, ScopeData> scopes = new LinkedHashMap<>();
    private String maintenanceWarning = "";

    ApplicationEnvironmentStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        persistence = new ApplicationEnvironmentStorePersistence(
                new File(new File(filesDir, "package-service"), "virtual-application-environment-v1.json"));
        load();
    }
    synchronized ApplicationEnvironmentProfileSnapshot getOrCreate(VirtualSystemServiceStore.Scope scope) {
        return data(scope).profile;
    }
    synchronized ApplicationEnvironmentProfileSnapshot update(VirtualSystemServiceStore.Scope scope,
            ApplicationEnvironmentProfileSnapshot requested) {
        if (requested == null) throw new IllegalArgumentException("application-environment profile is required");
        ScopeData state = data(scope); ScopeData before = state.copy();
        if (requested.policyVersion() != state.profile.policyVersion()) {
            throw new IllegalStateException("APPLICATION_ENV_PROFILE_VERSION_CONFLICT:expected="
                    + state.profile.policyVersion() + ":actual=" + requested.policyVersion());
        }
        state.profile = requested.withVersion(state.profile.policyVersion() + 1L, System.currentTimeMillis());
        enforcePolicyBounds(state); persistOrRestore(scope, before); return state.profile;
    }
    synchronized ApplicationEnvironmentProfileSnapshot reset(VirtualSystemServiceStore.Scope scope) {
        ScopeData state = data(scope); ScopeData before = state.copy();
        long next = state.profile.policyVersion() + 1L;
        state.profile = ApplicationEnvironmentDefaults.create(scope.packageName(), scope.virtualUserId(),
                next, System.currentTimeMillis());
        enforcePolicyBounds(state); persistOrRestore(scope, before); return state.profile;
    }
    synchronized List<VirtualShortcutSnapshot> shortcuts(VirtualSystemServiceStore.Scope scope) {
        List<VirtualShortcutSnapshot> out = new ArrayList<>(data(scope).shortcuts.values());
        out.sort(Comparator.comparingInt(VirtualShortcutSnapshot::rank).thenComparing(VirtualShortcutSnapshot::id));
        return List.copyOf(out);
    }
    synchronized boolean replaceDynamicShortcuts(VirtualSystemServiceStore.Scope scope,
            List<VirtualShortcutSnapshot> values) {
        ScopeData state = data(scope); requireStatic(state.profile.shortcut().mode(), "SHORTCUT");
        if (!state.profile.shortcut().enabled()) return false;
        List<VirtualShortcutSnapshot> bounded = shortcutValues(values, state.profile.shortcut().maximumDynamicShortcuts());
        ScopeData before = state.copy();
        state.shortcuts.entrySet().removeIf(item -> item.getValue().dynamic() && !item.getValue().pinned());
        for (VirtualShortcutSnapshot value : bounded) state.shortcuts.put(value.id(), value);
        enforceShortcutBounds(state); persistOrRestore(scope, before); return true;
    }
    synchronized boolean addDynamicShortcuts(VirtualSystemServiceStore.Scope scope,
            List<VirtualShortcutSnapshot> values) {
        ScopeData state = data(scope); requireStatic(state.profile.shortcut().mode(), "SHORTCUT");
        if (!state.profile.shortcut().enabled()) return false;
        List<VirtualShortcutSnapshot> bounded = shortcutValues(values, state.profile.shortcut().maximumDynamicShortcuts());
        ScopeData before = state.copy();
        for (VirtualShortcutSnapshot value : bounded) state.shortcuts.put(value.id(), value);
        enforceShortcutBounds(state); persistOrRestore(scope, before); return true;
    }
    synchronized void removeShortcuts(VirtualSystemServiceStore.Scope scope, List<String> ids) {
        ScopeData state = data(scope); requireStatic(state.profile.shortcut().mode(), "SHORTCUT");
        ScopeData before = state.copy();
        for (String id : boundedIds(ids)) {
            VirtualShortcutSnapshot value = state.shortcuts.get(id);
            if (value != null && !value.manifest()) state.shortcuts.remove(id);
        }
        persistOrRestore(scope, before);
    }
    synchronized void setShortcutsEnabled(VirtualSystemServiceStore.Scope scope, List<String> ids,
            boolean enabled, String disabledMessage) {
        ScopeData state = data(scope); requireStatic(state.profile.shortcut().mode(), "SHORTCUT");
        ScopeData before = state.copy(); long now = System.currentTimeMillis();
        for (String id : boundedIds(ids)) {
            VirtualShortcutSnapshot value = state.shortcuts.get(id);
            if (value != null) state.shortcuts.put(id, value.withEnabled(enabled, enabled ? "" : disabledMessage, now));
        }
        persistOrRestore(scope, before);
    }
    synchronized void reportShortcutUsed(VirtualSystemServiceStore.Scope scope, String id) {
        ScopeData state = data(scope); requireStatic(state.profile.shortcut().mode(), "SHORTCUT");
        VirtualShortcutSnapshot value = state.shortcuts.get(required(id, "shortcutId"));
        if (value == null) return;
        ScopeData before = state.copy(); state.shortcuts.put(id, value.withUsage(value.usageCount() + 1,
                System.currentTimeMillis())); persistOrRestore(scope, before);
    }
    synchronized int allocateAppWidgetId(VirtualSystemServiceStore.Scope scope, int hostId) {
        ScopeData state = data(scope); requireStatic(state.profile.appWidget().mode(), "APP_WIDGET");
        if (!state.profile.appWidget().enabled() || state.profile.appWidget().maximumWidgets() == 0) return 0;
        long hosts = state.widgets.values().stream().map(VirtualWidgetSnapshot::hostId).distinct().count();
        boolean existingHost = state.widgets.values().stream().anyMatch(item -> item.hostId() == hostId);
        if (!existingHost && hosts >= state.profile.appWidget().maximumHosts()) {
            throw new IllegalStateException("APP_WIDGET_HOST_LIMIT_EXCEEDED");
        }
        if (state.widgets.size() >= state.profile.appWidget().maximumWidgets()) {
            throw new IllegalStateException("APP_WIDGET_LIMIT_EXCEEDED");
        }
        ScopeData before = state.copy(); int id = nextWidgetId(state);
        state.widgets.put(id, new VirtualWidgetSnapshot(id, hostId, "", "", false,
                List.of(), List.of(), new byte[0], System.currentTimeMillis()));
        persistOrRestore(scope, before); return id;
    }
    synchronized boolean deleteAppWidgetId(VirtualSystemServiceStore.Scope scope, int appWidgetId) {
        ScopeData state = data(scope); ScopeData before = state.copy();
        boolean removed = state.widgets.remove(appWidgetId) != null;
        if (removed) persistOrRestore(scope, before); return removed;
    }
    synchronized List<VirtualWidgetSnapshot> appWidgets(VirtualSystemServiceStore.Scope scope, int hostId) {
        List<VirtualWidgetSnapshot> out = new ArrayList<>();
        for (VirtualWidgetSnapshot value : data(scope).widgets.values()) {
            if (hostId < 0 || value.hostId() == hostId) out.add(value);
        }
        out.sort(Comparator.comparingInt(VirtualWidgetSnapshot::appWidgetId)); return List.copyOf(out);
    }
    synchronized boolean bindAppWidget(VirtualSystemServiceStore.Scope scope, int appWidgetId,
            String providerPackage, String providerClass) {
        ScopeData state = data(scope); requireStatic(state.profile.appWidget().mode(), "APP_WIDGET");
        if (!state.profile.appWidget().allowBind()) return false;
        VirtualWidgetSnapshot current = state.widgets.get(appWidgetId); if (current == null) return false;
        ScopeData before = state.copy();
        state.widgets.put(appWidgetId, new VirtualWidgetSnapshot(appWidgetId, current.hostId(),
                providerPackage, providerClass, true, current.optionKeys(), current.optionValues(),
                current.remoteViewsPayload(), System.currentTimeMillis()));
        persistOrRestore(scope, before); return true;
    }
    synchronized void updateAppWidget(VirtualSystemServiceStore.Scope scope, VirtualWidgetSnapshot value) {
        if (value == null) throw new IllegalArgumentException("appWidget is required");
        ScopeData state = data(scope); requireStatic(state.profile.appWidget().mode(), "APP_WIDGET");
        VirtualWidgetSnapshot current = state.widgets.get(value.appWidgetId());
        if (current == null || current.hostId() != value.hostId()) {
            throw new IllegalStateException("APP_WIDGET_NOT_OWNED");
        }
        ScopeData before = state.copy(); state.widgets.put(value.appWidgetId(), value);
        persistOrRestore(scope, before);
    }
    synchronized void reportUsageEvent(VirtualSystemServiceStore.Scope scope, VirtualUsageEventSnapshot event) {
        if (event == null) throw new IllegalArgumentException("usage event is required");
        ScopeData state = data(scope); requireStatic(state.profile.usageStats().mode(), "USAGE_STATS");
        if (!state.profile.usageStats().enabled() || !state.profile.usageStats().allowReportEvents()) return;
        if (!scope.packageName().equals(event.packageName()) && !state.profile.usageStats().includeOtherPackages()) {
            throw new SecurityException("USAGE_EVENT_PACKAGE_DENIED");
        }
        ScopeData before = state.copy(); pruneUsage(state, System.currentTimeMillis());
        state.usageEvents.add(event);
        while (state.usageEvents.size() > state.profile.usageStats().maximumEvents()) state.usageEvents.remove(0);
        persistOrRestore(scope, before);
    }
    synchronized List<VirtualUsageEventSnapshot> usageEvents(VirtualSystemServiceStore.Scope scope,
            long beginMs, long endMs, int limit) {
        ScopeData state = data(scope); requireStatic(state.profile.usageStats().mode(), "USAGE_STATS");
        if (beginMs < 0L || endMs < beginMs || limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("usage query is invalid");
        }
        List<VirtualUsageEventSnapshot> out = new ArrayList<>();
        for (VirtualUsageEventSnapshot event : state.usageEvents) {
            if (event.timestampMs() < beginMs || event.timestampMs() > endMs) continue;
            if (!scope.packageName().equals(event.packageName()) && !state.profile.usageStats().includeOtherPackages()) continue;
            out.add(event); if (out.size() == limit) break;
        }
        out.sort(Comparator.comparingLong(VirtualUsageEventSnapshot::timestampMs)); return List.copyOf(out);
    }
    synchronized VirtualSettingSnapshot setting(VirtualSystemServiceStore.Scope scope, String namespace, String key) {
        ScopeData state = data(scope); requireStatic(state.profile.settings().mode(), "SETTINGS");
        String storageKey = settingKey(namespace, key);
        if (!state.profile.settings().namespaceAllowed(namespace) || state.profile.settings().keyBlocked(key)) return null;
        return state.settings.get(storageKey);
    }
    synchronized void putSetting(VirtualSystemServiceStore.Scope scope, VirtualSettingSnapshot value) {
        if (value == null) throw new IllegalArgumentException("setting is required");
        ScopeData state = data(scope); requireStatic(state.profile.settings().mode(), "SETTINGS");
        if (!state.profile.settings().writeAllowed(value.namespace()) || state.profile.settings().keyBlocked(value.key())) {
            throw new SecurityException("VIRTUAL_SETTINGS_WRITE_DENIED:" + value.namespace() + ":" + value.key());
        }
        String key = value.storageKey();
        if (!state.settings.containsKey(key) && state.settings.size() >= state.profile.settings().maximumEntries()) {
            throw new IllegalStateException("VIRTUAL_SETTINGS_ENTRY_LIMIT_EXCEEDED");
        }
        ScopeData before = state.copy(); state.settings.put(key, value); persistOrRestore(scope, before);
    }
    synchronized boolean deleteSetting(VirtualSystemServiceStore.Scope scope, String namespace, String key) {
        ScopeData state = data(scope); requireStatic(state.profile.settings().mode(), "SETTINGS");
        if (!state.profile.settings().writeAllowed(namespace) || state.profile.settings().keyBlocked(key)) {
            throw new SecurityException("VIRTUAL_SETTINGS_DELETE_DENIED:" + namespace + ":" + key);
        }
        ScopeData before = state.copy(); boolean removed = state.settings.remove(settingKey(namespace, key)) != null;
        if (removed) persistOrRestore(scope, before); return removed;
    }
    synchronized List<VirtualSettingSnapshot> settings(VirtualSystemServiceStore.Scope scope, String namespace) {
        ScopeData state = data(scope); requireStatic(state.profile.settings().mode(), "SETTINGS");
        List<VirtualSettingSnapshot> out = new ArrayList<>();
        for (VirtualSettingSnapshot value : state.settings.values()) {
            if (namespace == null || namespace.isBlank() || value.namespace().equalsIgnoreCase(namespace)) out.add(value);
        }
        out.sort(Comparator.comparing(VirtualSettingSnapshot::namespace).thenComparing(VirtualSettingSnapshot::key));
        return List.copyOf(out);
    }
    synchronized void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        ScopeData removed = scopes.remove(scope); if (removed == null) return;
        try { persist(); }
        catch (RuntimeException error) {
            scopes.put(scope, removed); maintenanceWarning = "APPLICATION_ENV_DELETE_PERSIST_FAILED:"
                    + String.valueOf(error.getMessage());
        }
    }
    synchronized String maintenanceWarning() { return maintenanceWarning; }
    synchronized int scopeCount() { return scopes.size(); }
    synchronized Map<VirtualSystemServiceStore.Scope, ScopeData> snapshot() {
        Map<VirtualSystemServiceStore.Scope, ScopeData> out = new LinkedHashMap<>();
        for (Map.Entry<VirtualSystemServiceStore.Scope, ScopeData> item : scopes.entrySet()) {
            out.put(item.getKey(), item.getValue().copy());
        }
        return out;
    }
    private ScopeData data(VirtualSystemServiceStore.Scope scope) {
        ScopeData current = scopes.get(scope); if (current != null) return current;
        ScopeData created = new ScopeData(ApplicationEnvironmentDefaults.create(
                scope.packageName(), scope.virtualUserId(), 1L, System.currentTimeMillis()),
                10_000 + scope.virtualUserId() * 1000);
        scopes.put(scope, created); persistOrRestore(scope, null); return created;
    }
    private void load() {
        try {
            String payload = persistence.readPayload();
            if (payload != null) scopes.putAll(ApplicationEnvironmentStoreCodec.decode(payload));
        } catch (RuntimeException error) {
            persistence.quarantine(); maintenanceWarning = error.getMessage() == null
                    ? "APPLICATION_ENV_STORE_CORRUPT" : error.getMessage(); scopes.clear();
        }
    }
    private void persistOrRestore(VirtualSystemServiceStore.Scope scope, ScopeData previous) {
        try { persist(); }
        catch (RuntimeException error) {
            if (previous == null) scopes.remove(scope); else scopes.put(scope, previous);
            throw error;
        }
    }
    private void persist() {
        if (scopes.size() > ApplicationEnvironmentStoreCodec.MAX_SCOPES) {
            throw new IllegalStateException("Application-environment scope limit exceeded");
        }
        persistence.writePayload(ApplicationEnvironmentStoreCodec.encode(scopes));
    }
    private static void requireStatic(String mode, String domain) {
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(mode)) {
            throw new IllegalStateException(domain + "_HOST_MODE_REQUIRES_FRAMEWORK_PASSTHROUGH");
        }
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(mode)) {
            throw new SecurityException(domain + "_BLOCKED");
        }
    }
    private static List<VirtualShortcutSnapshot> shortcutValues(List<VirtualShortcutSnapshot> values, int maximum) {
        List<VirtualShortcutSnapshot> out = values == null ? List.of() : new ArrayList<>(values);
        if (out.size() > maximum || out.contains(null)) throw new IllegalArgumentException("shortcut list is invalid");
        Map<String, VirtualShortcutSnapshot> unique = new LinkedHashMap<>();
        for (VirtualShortcutSnapshot value : out) {
            if (!value.dynamic()) throw new IllegalArgumentException("runtime shortcut must be dynamic");
            if (unique.put(value.id(), value) != null) throw new IllegalArgumentException("duplicate shortcut id");
        }
        return List.copyOf(unique.values());
    }
    private static List<String> boundedIds(List<String> ids) {
        List<String> out = ids == null ? List.of() : new ArrayList<>(ids);
        if (out.size() > 256 || out.contains(null)) throw new IllegalArgumentException("shortcut ids are invalid");
        return out;
    }
    private static void enforceShortcutBounds(ScopeData state) {
        long dynamic = state.shortcuts.values().stream().filter(VirtualShortcutSnapshot::dynamic).count();
        if (dynamic > state.profile.shortcut().maximumDynamicShortcuts()) {
            throw new IllegalStateException("DYNAMIC_SHORTCUT_LIMIT_EXCEEDED");
        }
        Map<String, Integer> perActivity = new LinkedHashMap<>();
        for (VirtualShortcutSnapshot value : state.shortcuts.values()) if (value.dynamic()) {
            String activity = value.activityClass();
            int count = perActivity.getOrDefault(activity, 0) + 1; perActivity.put(activity, count);
            if (count > state.profile.shortcut().maximumShortcutsPerActivity()) {
                throw new IllegalStateException("SHORTCUT_ACTIVITY_LIMIT_EXCEEDED:" + activity);
            }
            if (value.longLived() && !state.profile.shortcut().allowLongLived()) {
                throw new SecurityException("LONG_LIVED_SHORTCUT_DENIED");
            }
        }
    }
    private static void enforcePolicyBounds(ScopeData state) {
        enforceShortcutBounds(state);
        while (state.widgets.size() > state.profile.appWidget().maximumWidgets()) {
            Integer last = state.widgets.keySet().stream().max(Integer::compareTo).orElse(null);
            if (last == null) break; state.widgets.remove(last);
        }
        while (state.usageEvents.size() > state.profile.usageStats().maximumEvents()) state.usageEvents.remove(0);
        while (state.settings.size() > state.profile.settings().maximumEntries()) {
            String first = state.settings.keySet().iterator().next(); state.settings.remove(first);
        }
    }
    private static void pruneUsage(ScopeData state, long now) {
        long cutoff = Math.max(0L, now - state.profile.usageStats().retentionMs());
        state.usageEvents.removeIf(event -> event.timestampMs() < cutoff);
    }
    private static int nextWidgetId(ScopeData state) {
        int candidate = Math.max(1, state.nextWidgetId);
        while (state.widgets.containsKey(candidate)) candidate++;
        state.nextWidgetId = candidate == Integer.MAX_VALUE ? 1 : candidate + 1; return candidate;
    }
    private static String settingKey(String namespace, String key) {
        return required(namespace, "settingsNamespace").toLowerCase(java.util.Locale.ROOT)
                + ":" + required(key, "settingsKey");
    }
    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
