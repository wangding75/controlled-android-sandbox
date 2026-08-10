package com.warden.controlledsandbox;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAccountPage;
import com.warden.controlledsandbox.contract.VirtualAccountSummary;
import com.warden.controlledsandbox.contract.VirtualAlarmPage;
import com.warden.controlledsandbox.contract.VirtualJobPage;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelPage;
import com.warden.controlledsandbox.contract.VirtualNotificationPage;
import com.warden.controlledsandbox.contract.VirtualPageRequest;
import com.warden.controlledsandbox.contract.VirtualPendingIntentPage;
import com.warden.controlledsandbox.contract.VirtualSettingPage;
import com.warden.controlledsandbox.contract.VirtualShortcutPage;
import com.warden.controlledsandbox.contract.VirtualWidgetPage;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.contract.internal.DeathRegistrationHelper;
import java.io.File;

import static com.warden.controlledsandbox.PackageServiceDependencies.required;

final class PackageVirtualSystemServiceSession extends IVirtualSystemServiceSession.Stub
        implements IBinder.DeathRecipient, VirtualSystemServiceStore.Client {
    private final VirtualSystemServiceStore systemServices;
    private final VirtualDeviceServiceStore deviceServices;
    private final VirtualInteractionStore interactions;
    private final VirtualNetworkServiceStore networkServices;
    private final ApplicationEnvironmentStore applicationEnvironment;
    private final VirtualCompatibilityStore compatibility;
    private final VirtualPolicyServicesStore policyServices;
    private final VirtualMediaCommunicationStore mediaCommunication;
    private final VirtualPeripheralServicesStore peripheralServices;
    private final VirtualPrivilegedServicesStore privilegedServices;

    private final int ownerUid;
    private final PackageAuthorityCapabilityRegistry capabilityRegistry;
    private final IBinder authorityCapability;
    private final long authorityGeneration;
    private final String authorityRole;
    private final VirtualSystemServiceStore.Scope scope;
    private final int virtualUid;
    private final String processName;
    private final long generation;
    private final String packageRevision;
    private final DeathRegistrationHelper deathRegistration;
    private final VirtualSystemServicePager pager;
    private final String pagingScopeKey;
    private volatile boolean active = true;
    private volatile IVirtualSystemServiceObserver observer;

    PackageVirtualSystemServiceSession(PackageServiceDependencies dependencies,
            int ownerUid, IBinder clientToken,
                                VirtualSystemServiceStore.Scope scope, int virtualUid,
                                String processName, long generation, String packageRevision,
                                IBinder authorityCapability, long authorityGeneration,
                                String authorityRole) {
        java.util.Objects.requireNonNull(dependencies, "dependencies");
        capabilityRegistry = dependencies.capabilityRegistry;
        this.authorityCapability = java.util.Objects.requireNonNull(
                authorityCapability, "authorityCapability");
        this.authorityGeneration = authorityGeneration;
        this.authorityRole = required(authorityRole, "authorityRole");
        systemServices = dependencies.systemServices;
        deviceServices = dependencies.deviceServices;
        interactions = dependencies.interactions;
        networkServices = dependencies.networkServices;
        applicationEnvironment = dependencies.applicationEnvironment;
        compatibility = dependencies.compatibility;
        policyServices = dependencies.policyServices;
        mediaCommunication = dependencies.mediaCommunication;
        peripheralServices = dependencies.peripheralServices;
        privilegedServices = dependencies.privilegedServices;
        if (generation < 1L || virtualUid < 0) throw new IllegalArgumentException("virtual identity is invalid");
        this.ownerUid = ownerUid;
        this.scope = scope; this.virtualUid = virtualUid; this.processName = required(processName, "processName");
        this.generation = generation; this.packageRevision = required(packageRevision, "packageRevision");
        deathRegistration = new DeathRegistrationHelper(clientToken, this::binderDied);
        pager = new VirtualSystemServicePager(dependencies.filesDir);
        pagingScopeKey = scope.key() + "|vuid=" + virtualUid + "|process=" + this.processName
                + "|generation=" + generation + "|revision=" + this.packageRevision;
    }

    boolean linkClientDeathAfterReservation() throws android.os.RemoteException {
        boolean linked = deathRegistration.linkAfterReservation();
        if (!linked || !active || !deathRegistration.linkedAndAlive()) {
            closeInternal();
            return false;
        }
        return true;
    }
    @Override public byte[] getClipboard() { requireCapability(); return systemServices.clipboard(scope); }
    @Override public void setClipboard(byte[] payload) { requireCapability(); systemServices.setClipboard(scope, payload); }
    @Override public void clearClipboard() { requireCapability(); systemServices.clearClipboard(scope); }
    @Override public void registerObserver(IVirtualSystemServiceObserver value) {
        requireCapability(); observer = value;
    }
    @Override public VirtualAccountPage listAccountsPage(String type, VirtualPageRequest request) {
        requireCapability();
        String normalized = VirtualSystemServiceStore.normalize(type);
        VirtualSystemServicePager.PageSlice<VirtualAccountSummary> page = pager.page(
                "ACCOUNT:" + normalized, pagingScopeKey, systemServices.accountSummaries(scope, normalized),
                request, null);
        return new VirtualAccountPage(page.items(), page.blobs(), page.nextPageToken(), page.snapshotRevision());
    }
    @Override public java.util.List<VirtualAccountSnapshot> listAccounts(String type) {
        VirtualAccountPage page = listAccountsPage(type, legacyRequest());
        if (!page.nextPageToken().isEmpty() || !page.blobs().isEmpty()) {
            throw new IllegalStateException("PAGING_REQUIRED");
        }
        java.util.ArrayList<VirtualAccountSnapshot> out = new java.util.ArrayList<>();
        for (VirtualAccountSummary value : page.items()) {
            out.add(new VirtualAccountSnapshot(value.name(), value.type(), "", java.util.List.of(), java.util.List.of()));
        }
        return java.util.List.copyOf(out);
    }
    @Override public boolean addAccount(String name, String type, String password) {
        requireCapability(); return systemServices.addAccount(scope, name, type, password);
    }
    @Override public boolean removeAccount(String name, String type) {
        requireCapability(); return systemServices.removeAccount(scope, name, type);
    }
    @Override public void setPassword(String name, String type, String password) {
        requireCapability(); systemServices.setPassword(scope, name, type, password);
    }
    @Override public String getPassword(String name, String type) {
        requireCapability(); return systemServices.password(scope, name, type);
    }
    @Override public void setAuthToken(String name, String type, String tokenType, String token) {
        requireCapability(); systemServices.setToken(scope, name, type, tokenType, token);
    }
    @Override public String peekAuthToken(String name, String type, String tokenType) {
        requireCapability(); return systemServices.token(scope, name, type, tokenType);
    }
    @Override public void invalidateAuthToken(String accountType, String token) {
        requireCapability(); systemServices.invalidateToken(scope, accountType, token);
    }
    @Override public VirtualPendingIntentSnapshot reservePendingIntent(
            VirtualPendingIntentSnapshot candidate, boolean noCreate,
            boolean cancelCurrent, boolean updateCurrent) {
        requireCapability(); return systemServices.reservePendingIntent(scope, processName,
                generation, packageRevision, virtualUid, candidate, noCreate, cancelCurrent, updateCurrent);
    }
    @Override public VirtualPendingIntentSnapshot markPendingIntentSent(String tokenId) {
        requireCapability(); return systemServices.markPendingIntentSent(scope, packageRevision, tokenId);
    }
    @Override public boolean cancelPendingIntent(String tokenId) {
        requireCapability(); return systemServices.cancelPendingIntent(scope, packageRevision, tokenId);
    }
    @Override public VirtualPendingIntentPage listPendingIntentsPage(VirtualPageRequest request) {
        requireCapability();
        VirtualSystemServicePager.PageSlice<VirtualPendingIntentSnapshot> page = pager.page(
                "PENDING_INTENT", pagingScopeKey,
                systemServices.pendingIntents(scope, processName, generation, packageRevision),
                request, VirtualSystemServicePageAdapters.PENDING_INTENT);
        return new VirtualPendingIntentPage(page.items(), page.blobs(), page.nextPageToken(), page.snapshotRevision());
    }
    @Override public java.util.List<VirtualPendingIntentSnapshot> listPendingIntents() {
        requireCapability(); return pager.legacy(pager.page("PENDING_INTENT", pagingScopeKey,
                systemServices.pendingIntents(scope, processName, generation, packageRevision),
                legacyRequest(), VirtualSystemServicePageAdapters.PENDING_INTENT));
    }
    @Override public void scheduleAlarm(VirtualAlarmSnapshot candidate) {
        requireCapability(); systemServices.scheduleAlarm(scope, processName, generation,
                packageRevision, candidate);
    }
    @Override public boolean cancelAlarm(String alarmId) {
        requireCapability(); return systemServices.cancelAlarm(scope, packageRevision, alarmId);
    }
    @Override public VirtualAlarmPage listAlarmsPage(VirtualPageRequest request) {
        requireCapability();
        VirtualSystemServicePager.PageSlice<VirtualAlarmSnapshot> page = pager.page(
                "ALARM", pagingScopeKey,
                systemServices.alarms(scope, processName, generation, packageRevision),
                request, VirtualSystemServicePageAdapters.ALARM);
        return new VirtualAlarmPage(page.items(), page.blobs(), page.nextPageToken(), page.snapshotRevision());
    }
    @Override public java.util.List<VirtualAlarmSnapshot> listAlarms() {
        requireCapability(); return pager.legacy(pager.page("ALARM", pagingScopeKey,
                systemServices.alarms(scope, processName, generation, packageRevision),
                legacyRequest(), VirtualSystemServicePageAdapters.ALARM));
    }
    @Override public VirtualNotificationSnapshot reserveNotification(VirtualNotificationSnapshot candidate) {
        requireCapability(); return systemServices.reserveNotification(scope, generation, packageRevision, candidate);
    }
    @Override public void commitNotification(VirtualNotificationSnapshot value) {
        requireCapability(); systemServices.commitNotification(scope, packageRevision, value);
    }
    @Override public boolean removeNotification(int guestId, String guestTag) {
        requireCapability(); return systemServices.removeNotification(scope, packageRevision, guestId, guestTag);
    }
    @Override public VirtualNotificationPage listNotificationsPage(VirtualPageRequest request) {
        requireCapability();
        VirtualSystemServicePager.PageSlice<VirtualNotificationSnapshot> page = pager.page(
                "NOTIFICATION", pagingScopeKey, systemServices.notifications(scope, packageRevision),
                request, VirtualSystemServicePageAdapters.NOTIFICATION);
        return new VirtualNotificationPage(page.items(), page.blobs(), page.nextPageToken(), page.snapshotRevision());
    }
    @Override public java.util.List<VirtualNotificationSnapshot> listNotifications() {
        requireCapability(); return pager.legacy(pager.page("NOTIFICATION", pagingScopeKey,
                systemServices.notifications(scope, packageRevision), legacyRequest(),
                VirtualSystemServicePageAdapters.NOTIFICATION));
    }
    @Override public void upsertNotificationChannel(VirtualNotificationChannelSnapshot value) {
        requireCapability(); systemServices.upsertNotificationChannel(scope, packageRevision, value);
    }
    @Override public boolean removeNotificationChannel(String kind, String id) {
        requireCapability(); return systemServices.removeNotificationChannel(scope, packageRevision, kind, id);
    }
    @Override public VirtualNotificationChannelPage listNotificationChannelsPage(VirtualPageRequest request) {
        requireCapability();
        VirtualSystemServicePager.PageSlice<VirtualNotificationChannelSnapshot> page = pager.page(
                "NOTIFICATION_CHANNEL", pagingScopeKey,
                systemServices.notificationChannels(scope, packageRevision), request,
                VirtualSystemServicePageAdapters.CHANNEL);
        return new VirtualNotificationChannelPage(page.items(), page.blobs(),
                page.nextPageToken(), page.snapshotRevision());
    }
    @Override public java.util.List<VirtualNotificationChannelSnapshot> listNotificationChannels() {
        requireCapability(); return pager.legacy(pager.page("NOTIFICATION_CHANNEL", pagingScopeKey,
                systemServices.notificationChannels(scope, packageRevision), legacyRequest(),
                VirtualSystemServicePageAdapters.CHANNEL));
    }
    @Override public VirtualJobSnapshot reserveJob(VirtualJobSnapshot candidate) {
        requireCapability(); return systemServices.reserveJob(scope, processName, generation,
                packageRevision, candidate);
    }
    @Override public void commitJob(int guestId) { requireCapability(); systemServices.commitJob(scope, guestId); }
    @Override public boolean removeJob(int guestId) { requireCapability(); return systemServices.removeJob(scope, guestId); }
    @Override public VirtualJobPage listJobsPage(VirtualPageRequest request) {
        requireCapability();
        VirtualSystemServicePager.PageSlice<VirtualJobSnapshot> page = pager.page(
                "JOB", pagingScopeKey, systemServices.jobs(scope, processName, generation, packageRevision),
                request, VirtualSystemServicePageAdapters.JOB);
        return new VirtualJobPage(page.items(), page.blobs(), page.nextPageToken(), page.snapshotRevision());
    }
    @Override public java.util.List<VirtualJobSnapshot> listJobs() {
        requireCapability(); return pager.legacy(pager.page("JOB", pagingScopeKey,
                systemServices.jobs(scope, processName, generation, packageRevision), legacyRequest(),
                VirtualSystemServicePageAdapters.JOB));
    }
    @Override public int ensureNamespace(String namespace, int guestId) {
        requireCapability(); return systemServices.ensureNamespace(scope, namespace, guestId);
    }
    @Override public int hostIdIfPresent(String namespace, int guestId) {
        requireCapability(); return systemServices.hostIdIfPresent(scope, namespace, guestId);
    }
    @Override public int guestIdForHost(String namespace, int hostId) {
        requireCapability(); return systemServices.guestIdForHost(scope, namespace, hostId);
    }
    @Override public int removeNamespace(String namespace, int guestId) {
        requireCapability(); return systemServices.removeNamespace(scope, namespace, guestId);
    }
    @Override public int[] listNamespaceGuestIds(String namespace) {
        requireCapability(); return systemServices.namespaceGuestIds(scope, namespace);
    }
    @Override public VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile() {
        requireCapability(); return deviceServices.getOrCreate(scope);
    }
    @Override public VirtualInteractionProfileSnapshot getInteractionProfile() {
        requireCapability(); return interactions.getOrCreate(scope);
    }
    @Override public VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile() {
        requireCapability(); return networkServices.getOrCreate(scope);
    }
    @Override public ApplicationEnvironmentProfileSnapshot getApplicationEnvironmentProfile() {
        requireCapability(); return applicationEnvironment.getOrCreate(scope);
    }
    @Override public VirtualCompatibilityProfileSnapshot getCompatibilityProfile() {
        requireCapability(); return compatibility.getOrCreate(scope);
    }
    @Override public VirtualPolicyServicesProfileSnapshot getPolicyServicesProfile() {
        requireCapability(); return policyServices.getOrCreate(scope);
    }
    @Override public VirtualMediaCommunicationProfileSnapshot getMediaCommunicationProfile() {
        requireCapability(); return mediaCommunication.getOrCreate(scope);
    }
    @Override public VirtualPeripheralServicesProfileSnapshot getPeripheralServicesProfile() {
        requireCapability(); return peripheralServices.getOrCreate(scope);
    }
    @Override public VirtualPrivilegedServicesProfileSnapshot getPrivilegedServicesProfile() {
        requireCapability(); return privilegedServices.getOrCreate(scope);
    }
    @Override public VirtualShortcutPage listShortcutsPage(VirtualPageRequest request) {
        requireCapability();
        VirtualSystemServicePager.PageSlice<VirtualShortcutSnapshot> page = pager.page(
                "SHORTCUT", pagingScopeKey, applicationEnvironment.shortcuts(scope), request, null);
        return new VirtualShortcutPage(page.items(), page.blobs(), page.nextPageToken(), page.snapshotRevision());
    }
    @Override public java.util.List<VirtualShortcutSnapshot> listShortcuts() {
        requireCapability(); return pager.legacy(pager.page("SHORTCUT", pagingScopeKey,
                applicationEnvironment.shortcuts(scope), legacyRequest(), null));
    }
    @Override public boolean replaceDynamicShortcuts(java.util.List<VirtualShortcutSnapshot> shortcuts) {
        requireCapability(); boolean changed = applicationEnvironment.replaceDynamicShortcuts(scope, shortcuts);
        if (changed) systemServices.notifyApplicationEnvironmentDataChanged(scope, "SHORTCUT", "*");
        return changed;
    }
    @Override public boolean addDynamicShortcuts(java.util.List<VirtualShortcutSnapshot> shortcuts) {
        requireCapability(); boolean changed = applicationEnvironment.addDynamicShortcuts(scope, shortcuts);
        if (changed) systemServices.notifyApplicationEnvironmentDataChanged(scope, "SHORTCUT", "*");
        return changed;
    }
    @Override public void removeShortcuts(java.util.List<String> shortcutIds) {
        requireCapability(); applicationEnvironment.removeShortcuts(scope, shortcutIds);
        systemServices.notifyApplicationEnvironmentDataChanged(scope, "SHORTCUT", "*");
    }
    @Override public void setShortcutsEnabled(java.util.List<String> shortcutIds,
            boolean enabled, String disabledMessage) {
        requireCapability(); applicationEnvironment.setShortcutsEnabled(scope, shortcutIds, enabled, disabledMessage);
        systemServices.notifyApplicationEnvironmentDataChanged(scope, "SHORTCUT", "*");
    }
    @Override public void reportShortcutUsed(String shortcutId) {
        requireCapability(); applicationEnvironment.reportShortcutUsed(scope, shortcutId);
        systemServices.notifyApplicationEnvironmentDataChanged(scope, "SHORTCUT", shortcutId);
    }
    @Override public int allocateAppWidgetId(int hostId) {
        requireCapability(); int id = applicationEnvironment.allocateAppWidgetId(scope, hostId);
        if (id > 0) systemServices.notifyApplicationEnvironmentDataChanged(scope, "APP_WIDGET", String.valueOf(id));
        return id;
    }
    @Override public boolean deleteAppWidgetId(int appWidgetId) {
        requireCapability(); boolean removed = applicationEnvironment.deleteAppWidgetId(scope, appWidgetId);
        if (removed) systemServices.notifyApplicationEnvironmentDataChanged(scope, "APP_WIDGET", String.valueOf(appWidgetId));
        return removed;
    }
    @Override public VirtualWidgetPage listAppWidgetsPage(int hostId, VirtualPageRequest request) {
        requireCapability();
        VirtualSystemServicePager.PageSlice<VirtualWidgetSnapshot> page = pager.page(
                "APP_WIDGET:" + hostId, pagingScopeKey, applicationEnvironment.appWidgets(scope, hostId),
                request, VirtualSystemServicePageAdapters.WIDGET);
        return new VirtualWidgetPage(page.items(), page.blobs(), page.nextPageToken(), page.snapshotRevision());
    }
    @Override public java.util.List<VirtualWidgetSnapshot> listAppWidgets(int hostId) {
        requireCapability(); return pager.legacy(pager.page("APP_WIDGET:" + hostId, pagingScopeKey,
                applicationEnvironment.appWidgets(scope, hostId), legacyRequest(),
                VirtualSystemServicePageAdapters.WIDGET));
    }
    @Override public boolean bindAppWidgetId(int appWidgetId, String providerPackage, String providerClass) {
        requireCapability(); boolean bound = applicationEnvironment.bindAppWidget(scope, appWidgetId,
                providerPackage, providerClass);
        if (bound) systemServices.notifyApplicationEnvironmentDataChanged(scope, "APP_WIDGET", String.valueOf(appWidgetId));
        return bound;
    }
    @Override public void updateAppWidget(VirtualWidgetSnapshot appWidget) {
        requireCapability(); applicationEnvironment.updateAppWidget(scope, appWidget);
        systemServices.notifyApplicationEnvironmentDataChanged(scope, "APP_WIDGET", String.valueOf(appWidget.appWidgetId()));
    }
    @Override public void reportUsageEvent(VirtualUsageEventSnapshot event) {
        requireCapability(); applicationEnvironment.reportUsageEvent(scope, event);
        systemServices.notifyApplicationEnvironmentDataChanged(scope, "USAGE", String.valueOf(event.eventType()));
    }
    @Override public java.util.List<VirtualUsageEventSnapshot> queryUsageEvents(
            long beginMs, long endMs, int limit) {
        requireCapability(); return applicationEnvironment.usageEvents(scope, beginMs, endMs, limit);
    }
    @Override public VirtualSettingSnapshot getSetting(String namespace, String key) {
        requireCapability(); return applicationEnvironment.setting(scope, namespace, key);
    }
    @Override public void putSetting(VirtualSettingSnapshot setting) {
        requireCapability(); applicationEnvironment.putSetting(scope, setting);
        systemServices.notifyApplicationEnvironmentDataChanged(scope, "SETTINGS", setting.storageKey());
    }
    @Override public boolean deleteSetting(String namespace, String key) {
        requireCapability(); boolean removed = applicationEnvironment.deleteSetting(scope, namespace, key);
        if (removed) systemServices.notifyApplicationEnvironmentDataChanged(scope, "SETTINGS", namespace + ":" + key);
        return removed;
    }
    @Override public VirtualSettingPage listSettingsPage(String namespace, VirtualPageRequest request) {
        requireCapability();
        String normalized = namespace == null ? "" : namespace.trim();
        VirtualSystemServicePager.PageSlice<VirtualSettingSnapshot> page = pager.page(
                "SETTING:" + normalized, pagingScopeKey, applicationEnvironment.settings(scope, normalized),
                request, null);
        return new VirtualSettingPage(page.items(), page.blobs(), page.nextPageToken(), page.snapshotRevision());
    }
    @Override public java.util.List<VirtualSettingSnapshot> listSettings(String namespace) {
        requireCapability();
        String normalized = namespace == null ? "" : namespace.trim();
        return pager.legacy(pager.page("SETTING:" + normalized, pagingScopeKey,
                applicationEnvironment.settings(scope, normalized), legacyRequest(), null));
    }
    @Override public ParcelFileDescriptor openPageBlob(String blobToken) {
        requireCapability(); return pager.openBlob(pagingScopeKey, blobToken);
    }
    @Override public void close() { requireCapability(); closeInternal(); }
    @Override public void binderDied() { closeInternal(); }
    @Override public VirtualSystemServiceStore.Scope scope() { return scope; }
    @Override public String processName() { return processName; }
    @Override public long generation() { return generation; }
    @Override public IVirtualSystemServiceObserver observer() { return observer; }
    @Override public boolean active() { return active; }

    private void requireCapability() {
        if (!active || Binder.getCallingUid() != ownerUid) {
            throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DENIED");
        }
        capabilityRegistry.requireRuntimeSession(authorityRole, authorityCapability,
                authorityGeneration);
    }
    private synchronized void closeInternal() {
        if (!active) return;
        active = false;
        observer = null;
        systemServices.unregister(this);
        deathRegistration.close();
        pager.close();
    }

    private static VirtualPageRequest legacyRequest() {
        return new VirtualPageRequest(VirtualSystemServicePager.LEGACY_MAX_ITEMS,
                VirtualSystemServicePager.LEGACY_MAX_BYTES, "");
    }
}
