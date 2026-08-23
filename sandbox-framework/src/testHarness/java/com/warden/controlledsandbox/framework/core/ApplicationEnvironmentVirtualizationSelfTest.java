package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;
import com.warden.controlledsandbox.contract.VirtualLauncherProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageStatsPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualUserProfileSnapshot;
import com.warden.controlledsandbox.framework.capability.CapabilityAuditSink;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class ApplicationEnvironmentVirtualizationSelfTest {
    public static void main(String[] args) {
        FakeAuthority authority = new FakeAuthority(profile(VirtualLocationProfileSnapshot.MODE_STATIC));
        GuestIdentity identity = identity(authority);

        UserApi user = proxy(UserApi.class, identity, "userManager");
        require(user.getUserHandle() == 3, "virtual user handle");
        require(user.isUserUnlocked(), "virtual user unlocked");
        require(user.getUserRestrictions().getBoolean("no_install_unknown_sources", false), "user restriction");

        RestrictionsApi restrictions = proxy(RestrictionsApi.class, identity, "restrictions");
        require("true".equals(restrictions.getApplicationRestrictions("guest.pkg").getString("managed")),
                "application restriction projection");
        require(!restrictions.hasRestrictionsProvider(), "host restrictions provider hidden");
        boolean restrictionsMutationDenied = false;
        try { restrictions.requestPermission("guest.pkg", "camera", "request", new Bundle()); }
        catch (SecurityException expected) { restrictionsMutationDenied = true; }
        require(restrictionsMutationDenied, "restrictions mutation denied");

        LauncherApi launcher = proxy(LauncherApi.class, identity, "launcherApps");
        require(launcher.isPackageEnabled("guest.pkg", null), "launcher package visibility");
        require(launcher.getLauncherActivities("guest.pkg", null).size() == 1, "launcher activity projection");
        Object listener = new Object();
        launcher.registerCallback("guest.pkg", listener);
        launcher.unregisterCallback("guest.pkg", listener);
        launcher.addOnAppsChangedListener("guest.pkg", listener);
        launcher.removeOnAppsChangedListener(listener);
        launcher.registerCallback("guest.pkg", new Object());
        launcher.registerCallback("guest.pkg", new Object());

        ShortcutApi shortcuts = proxy(ShortcutApi.class, identity, "shortcut");
        require(shortcuts.setDynamicShortcuts("guest.pkg", List.of(new FakeShortcut("compose", "Compose")), 3),
                "shortcut mutation");
        require(shortcuts.getDynamicShortcuts("guest.pkg", 3).size() == 1, "shortcut query");
        shortcuts.reportShortcutUsed("guest.pkg", "compose", 3);
        require(authority.shortcuts.get(0).usageCount() == 1, "shortcut usage count");
        require(shortcuts.getMaxShortcutCountPerActivity("guest.pkg", 3) == 15, "shortcut quota");

        AppWidgetApi widgets = proxy(AppWidgetApi.class, identity, "appWidget");
        int id = widgets.allocateAppWidgetId("guest.pkg", 9);
        require(id == 10001, "widget allocation");
        require(widgets.bindAppWidgetId("guest.pkg", id, 3,
                new FakeComponent("guest.pkg", ".WidgetProvider")), "widget bind");
        require(widgets.getAppWidgetIdsForHost("guest.pkg", 9).length == 1, "widget ids");
        require(widgets.getAppWidgetInfo("guest.pkg", id, 3) != null, "widget info");

        UsageApi usage = proxy(UsageApi.class, identity, "usageStats");
        long now = System.currentTimeMillis();
        usage.reportEvent("guest.pkg", 1, now);
        require(usage.queryEvents(now - 1L, now + 1L, "guest.pkg").size() == 1, "usage query");
        require(usage.getAppStandbyBucket("guest.pkg", "guest.pkg", 3) == 10, "standby bucket");

        ContentApi content = proxy(ContentApi.class, identity, "content");
        AtomicInteger changes = new AtomicInteger();
        FakeObserver observer = new FakeObserver(changes);
        content.registerContentObserver("content://settings/secure/theme_mode", false, observer, 3);
        content.notifyChange("content://settings/secure/theme_mode", null, 3, 3);
        require(changes.get() == 1, "content observer delivery");
        CollectionObserver collectionObserver = new CollectionObserver();
        content.registerContentObserver("content://settings/secure/theme_mode", false,
                collectionObserver, 3);
        content.notifyChange("content://settings/secure/theme_mode", null, 3, 3);
        require(collectionObserver.calls == 1 && collectionObserver.uriCount == 1
                        && collectionObserver.flags == 3,
                "content observer collection/flags projection");
        content.unregisterContentObserver(observer);
        content.unregisterContentObserver(collectionObserver);
        content.notifyChange("content://settings/secure/theme_mode", null, 3, 3);
        require(changes.get() == 2,
                "unregister content observer removes rather than re-registers the callback");
        require(content.getSyncAdapterTypes().isEmpty(), "sync adapters fail closed");

        FakeAuthority hostAuthority = new FakeAuthority(profile(VirtualLocationProfileSnapshot.MODE_HOST));
        GuestIdentity hostIdentity = identity(hostAuthority);
        HostUserDelegate hostDelegate = new HostUserDelegate();
        UserApi host = (UserApi) Proxy.newProxyInstance(UserApi.class.getClassLoader(),
                new Class<?>[]{UserApi.class}, new SystemServiceInvocationHandler(hostDelegate, hostIdentity, "userManager"));
        require(host.getUserHandle() == 77, "HOST user manager passthrough");

        System.out.println("PASS M5-T11 application-environment virtualization self-test");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> api, GuestIdentity identity, String service) {
        Object delegate = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api},
                (ignored, method, arguments) -> defaultValue(method.getReturnType()));
        return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api},
                new SystemServiceInvocationHandler(delegate, identity, service));
    }

    private static GuestIdentity identity(FakeAuthority authority) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = "guest.pkg";
        VirtualPackageMetadata metadata = new VirtualPackageMetadata("guest.pkg", ".MainActivity", app, List.of());
        return new GuestIdentity("guest.pkg", 19003, app, Set.of(), "host.pkg", 10001,
                metadata, "guest.pkg", 3, 1L, new VirtualPermissionPolicy(Set.of(), Map.of()),
                new SandboxAppOpsPolicy(Map.of()), CapabilityAuditSink.NO_OP,
                new CapabilityLeaseRegistry(), new VirtualSystemServiceState(authority), "rev-a");
    }

    private static ApplicationEnvironmentProfileSnapshot profile(String mode) {
        return new ApplicationEnvironmentProfileSnapshot(1L, 0L,
                new VirtualUserProfileSnapshot(mode, 3, 100003L, "Sandbox user 3", 0,
                        true, true, false, List.of("no_install_unknown_sources"),
                        List.of("managed"), List.of("true")),
                new VirtualLauncherProfileSnapshot(mode, true, true, true, 2,
                        List.of("guest.pkg"), List.of()),
                new VirtualShortcutPolicySnapshot(mode, true, 15, 64, 100, 0L, true, true),
                new VirtualWidgetPolicySnapshot(mode, true, true, true, 32, 8),
                new VirtualUsageStatsPolicySnapshot(mode, true, 604800000L, 100, true, false),
                new VirtualSettingsProfileSnapshot(mode, true, true, false, 128,
                        List.of("secure", "system", "global"), List.of("adb_enabled")));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (List.class.isAssignableFrom(type)) return List.of();
        if (type.isArray()) return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        return null;
    }

    interface UserApi {
        int getUserHandle(); boolean isUserUnlocked(); Bundle getUserRestrictions();
    }
    static final class HostUserDelegate implements UserApi {
        public int getUserHandle() { return 77; }
        public boolean isUserUnlocked() { return false; }
        public Bundle getUserRestrictions() { return new Bundle(); }
    }
    interface RestrictionsApi {
        Bundle getApplicationRestrictions(String packageName);
        boolean hasRestrictionsProvider();
        void requestPermission(String packageName, String requestType, String requestId, Bundle request);
    }
    interface LauncherApi {
        boolean isPackageEnabled(String packageName, Object user);
        List<Object> getLauncherActivities(String packageName, Object user);
        void registerCallback(String packageName, Object callback);
        void unregisterCallback(String packageName, Object callback);
        void addOnAppsChangedListener(String packageName, Object callback);
        void removeOnAppsChangedListener(Object callback);
    }
    interface ShortcutApi {
        boolean setDynamicShortcuts(String packageName, List<FakeShortcut> shortcuts, int userId);
        List<Object> getDynamicShortcuts(String packageName, int userId);
        void reportShortcutUsed(String packageName, String shortcutId, int userId);
        int getMaxShortcutCountPerActivity(String packageName, int userId);
    }
    interface AppWidgetApi {
        int allocateAppWidgetId(String packageName, int hostId);
        boolean bindAppWidgetId(String packageName, int appWidgetId, int userId, FakeComponent provider);
        int[] getAppWidgetIdsForHost(String packageName, int hostId);
        Object getAppWidgetInfo(String packageName, int appWidgetId, int userId);
    }
    interface UsageApi {
        void reportEvent(String packageName, int eventType, long timestamp);
        List<Object> queryEvents(long begin, long end, String packageName);
        int getAppStandbyBucket(String callingPackage, String packageName, int userId);
    }
    interface ContentApi {
        void registerContentObserver(String uri, boolean descendants, Object observer, int userId);
        void unregisterContentObserver(Object observer);
        void notifyChange(String uri, Object observer, int flags, int userId);
        List<Object> getSyncAdapterTypes();
    }
    static final class FakeShortcut {
        private final String id, shortLabel;
        FakeShortcut(String id, String shortLabel) { this.id = id; this.shortLabel = shortLabel; }
        public String getId() { return id; }
        public String getShortLabel() { return shortLabel; }
        public int getRank() { return 0; }
        public boolean isEnabled() { return true; }
        public boolean isPinned() { return false; }
        public boolean isDeclaredInManifest() { return false; }
        public boolean isLongLived() { return false; }
    }
    static final class FakeComponent {
        private final String pkg, cls;
        FakeComponent(String pkg, String cls) { this.pkg = pkg; this.cls = cls; }
        public String getPackageName() { return pkg; }
        public String getClassName() { return cls; }
    }
    static final class FakeObserver {
        private final AtomicInteger changes;
        FakeObserver(AtomicInteger changes) { this.changes = changes; }
        public void onChange(boolean selfChange) { changes.incrementAndGet(); }
    }
    static final class CollectionObserver {
        int calls;
        int uriCount;
        int flags;
        public void onChange(boolean selfChange, java.util.Collection<android.net.Uri> uris,
                             int flags) {
            calls++;
            uriCount = uris == null ? 0 : uris.size();
            this.flags = flags;
        }
    }

    static final class FakeAuthority implements VirtualSystemServiceAuthority {
        final ApplicationEnvironmentProfileSnapshot profile;
        final List<VirtualShortcutSnapshot> shortcuts = new ArrayList<>();
        final Map<Integer, VirtualWidgetSnapshot> widgets = new LinkedHashMap<>();
        final List<VirtualUsageEventSnapshot> usage = new ArrayList<>();
        final Map<String, VirtualSettingSnapshot> settings = new LinkedHashMap<>();
        int nextWidgetId = 10001;
        FakeAuthority(ApplicationEnvironmentProfileSnapshot profile) { this.profile = profile; }
        @Override public ApplicationEnvironmentProfileSnapshot applicationEnvironmentProfile() { return profile; }
        @Override public List<VirtualShortcutSnapshot> shortcuts() { return List.copyOf(shortcuts); }
        @Override public boolean replaceDynamicShortcuts(List<VirtualShortcutSnapshot> values) {
            shortcuts.clear(); shortcuts.addAll(values); return true;
        }
        @Override public boolean addDynamicShortcuts(List<VirtualShortcutSnapshot> values) {
            shortcuts.addAll(values); return true;
        }
        @Override public void removeShortcuts(List<String> ids) { shortcuts.removeIf(value -> ids.contains(value.id())); }
        @Override public void setShortcutsEnabled(List<String> ids, boolean enabled, String message) {
            for (int index = 0; index < shortcuts.size(); index++) if (ids.contains(shortcuts.get(index).id())) {
                shortcuts.set(index, shortcuts.get(index).withEnabled(enabled, message, System.currentTimeMillis()));
            }
        }
        @Override public void reportShortcutUsed(String id) {
            for (int index = 0; index < shortcuts.size(); index++) if (shortcuts.get(index).id().equals(id)) {
                VirtualShortcutSnapshot value = shortcuts.get(index);
                shortcuts.set(index, value.withUsage(value.usageCount() + 1, System.currentTimeMillis()));
            }
        }
        @Override public int allocateAppWidgetId(int hostId) {
            int id = nextWidgetId++;
            widgets.put(id, new VirtualWidgetSnapshot(id, hostId, "", "", false,
                    List.of(), List.of(), new byte[0], System.currentTimeMillis()));
            return id;
        }
        @Override public boolean deleteAppWidgetId(int id) { return widgets.remove(id) != null; }
        @Override public List<VirtualWidgetSnapshot> appWidgets(int hostId) {
            return widgets.values().stream().filter(value -> hostId < 0 || value.hostId() == hostId).toList();
        }
        @Override public boolean bindAppWidgetId(int id, String pkg, String cls) {
            VirtualWidgetSnapshot current = widgets.get(id); if (current == null) return false;
            widgets.put(id, new VirtualWidgetSnapshot(id, current.hostId(), pkg, cls, true,
                    List.of(), List.of(), new byte[0], System.currentTimeMillis())); return true;
        }
        @Override public void updateAppWidget(VirtualWidgetSnapshot value) { widgets.put(value.appWidgetId(), value); }
        @Override public void reportUsageEvent(VirtualUsageEventSnapshot event) { usage.add(event); }
        @Override public List<VirtualUsageEventSnapshot> usageEvents(long begin, long end, int limit) {
            return usage.stream().filter(value -> value.timestampMs() >= begin && value.timestampMs() <= end)
                    .limit(limit).toList();
        }
        @Override public VirtualSettingSnapshot setting(String namespace, String key) { return settings.get(namespace + ":" + key); }
        @Override public void putSetting(VirtualSettingSnapshot setting) { settings.put(setting.storageKey(), setting); }
        @Override public boolean deleteSetting(String namespace, String key) { return settings.remove(namespace + ":" + key) != null; }
        @Override public List<VirtualSettingSnapshot> settings(String namespace) { return List.copyOf(settings.values()); }
        public Object clipboard() { return null; }
        public void setClipboard(Object value) { }
        public void clearClipboard() { }
        public void setClipboardChangeListener(Runnable listener) { }
        public List<AccountRecord> accounts(String type) { return List.of(); }
        public boolean addAccount(String name, String type, String password) { return false; }
        public boolean removeAccount(String name, String type) { return false; }
        public void setPassword(String name, String type, String password) { }
        public String password(String name, String type) { return null; }
        public void setToken(String name, String type, String tokenType, String token) { }
        public String token(String name, String type, String tokenType) { return null; }
        public void invalidateToken(String accountType, String token) { }
        public void scheduleAlarm(AlarmRecord candidate, Runnable delivery) { }
        public boolean cancelAlarm(String alarmId) { return false; }
        public List<AlarmRecord> alarms() { return List.of(); }
        public NamespaceMapping ensureNamespace(String namespace, int guestId) { return new NamespaceMapping(guestId, true); }
        public Integer hostIdIfPresent(String namespace, int guestId) { return null; }
        public Integer guestId(String namespace, int hostId) { return null; }
        public Integer removeNamespace(String namespace, int guestId) { return null; }
        public List<Integer> guestIds(String namespace) { return List.of(); }
        public int namespaceSize(String namespace) { return 0; }
        public void close() { }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
