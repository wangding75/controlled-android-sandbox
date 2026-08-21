package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.VirtualPageRequest;
import com.warden.controlledsandbox.contract.VirtualAccountPage;
import com.warden.controlledsandbox.contract.VirtualPendingIntentPage;
import com.warden.controlledsandbox.contract.VirtualAlarmPage;
import com.warden.controlledsandbox.contract.VirtualNotificationPage;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelPage;
import com.warden.controlledsandbox.contract.VirtualJobPage;
import com.warden.controlledsandbox.contract.VirtualShortcutPage;
import com.warden.controlledsandbox.contract.VirtualWidgetPage;
import com.warden.controlledsandbox.contract.VirtualSettingPage;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
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

interface IVirtualSystemServiceSession {
    byte[] getClipboard();
    void setClipboard(in byte[] payload);
    void clearClipboard();
    void registerObserver(IVirtualSystemServiceObserver observer);

    List<VirtualAccountSnapshot> listAccounts(String type);
    boolean addAccount(String name, String type, String password);
    boolean removeAccount(String name, String type);
    void setPassword(String name, String type, String password);
    String getPassword(String name, String type);
    void setAuthToken(String name, String type, String tokenType, String token);
    String peekAuthToken(String name, String type, String tokenType);
    void invalidateAuthToken(String accountType, String token);

    VirtualPendingIntentSnapshot reservePendingIntent(in VirtualPendingIntentSnapshot candidate,
            boolean noCreate, boolean cancelCurrent, boolean updateCurrent);
    VirtualPendingIntentSnapshot markPendingIntentSent(String tokenId);
    boolean cancelPendingIntent(String tokenId);
    List<VirtualPendingIntentSnapshot> listPendingIntents();

    void scheduleAlarm(in VirtualAlarmSnapshot candidate);
    boolean cancelAlarm(String alarmId);
    List<VirtualAlarmSnapshot> listAlarms();

    VirtualNotificationSnapshot reserveNotification(in VirtualNotificationSnapshot candidate);
    void commitNotification(in VirtualNotificationSnapshot value);
    boolean removeNotification(int guestId, String guestTag);
    List<VirtualNotificationSnapshot> listNotifications();
    void upsertNotificationChannel(in VirtualNotificationChannelSnapshot value);
    boolean removeNotificationChannel(String kind, String id);
    List<VirtualNotificationChannelSnapshot> listNotificationChannels();

    VirtualJobSnapshot reserveJob(in VirtualJobSnapshot candidate);
    void commitJob(int guestId);
    boolean removeJob(int guestId);
    List<VirtualJobSnapshot> listJobs();

    int ensureNamespace(String namespace, int guestId);
    int hostIdIfPresent(String namespace, int guestId);
    int guestIdForHost(String namespace, int hostId);
    int removeNamespace(String namespace, int guestId);
    int[] listNamespaceGuestIds(String namespace);

    VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile();
    VirtualInteractionProfileSnapshot getInteractionProfile();
    VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile();
    ApplicationEnvironmentProfileSnapshot getApplicationEnvironmentProfile();
    VirtualCompatibilityProfileSnapshot getCompatibilityProfile();
    VirtualPolicyServicesProfileSnapshot getPolicyServicesProfile();
    VirtualMediaCommunicationProfileSnapshot getMediaCommunicationProfile();
    VirtualPeripheralServicesProfileSnapshot getPeripheralServicesProfile();
    VirtualPrivilegedServicesProfileSnapshot getPrivilegedServicesProfile();

    List<VirtualShortcutSnapshot> listShortcuts();
    boolean replaceDynamicShortcuts(in List<VirtualShortcutSnapshot> shortcuts);
    boolean addDynamicShortcuts(in List<VirtualShortcutSnapshot> shortcuts);
    void removeShortcuts(in List<String> shortcutIds);
    void setShortcutsEnabled(in List<String> shortcutIds, boolean enabled, String disabledMessage);
    void reportShortcutUsed(String shortcutId);

    int allocateAppWidgetId(int hostId);
    boolean deleteAppWidgetId(int appWidgetId);
    List<VirtualWidgetSnapshot> listAppWidgets(int hostId);
    boolean bindAppWidgetId(int appWidgetId, String providerPackage, String providerClass);
    void updateAppWidget(in VirtualWidgetSnapshot appWidget);

    void reportUsageEvent(in VirtualUsageEventSnapshot event);
    List<VirtualUsageEventSnapshot> queryUsageEvents(long beginMs, long endMs, int limit);

    VirtualSettingSnapshot getSetting(String namespace, String key);
    void putSetting(in VirtualSettingSnapshot setting);
    boolean deleteSetting(String namespace, String key);
    List<VirtualSettingSnapshot> listSettings(String namespace);

    void close();

    // Appended in M5-T19.1-E. Keep every pre-existing method above in its original order so
    // generated Binder transaction IDs remain compatible with older Host/Runtime components.
    VirtualAccountPage listAccountsPage(String type, in VirtualPageRequest request);
    VirtualPendingIntentPage listPendingIntentsPage(in VirtualPageRequest request);
    VirtualAlarmPage listAlarmsPage(in VirtualPageRequest request);
    VirtualNotificationPage listNotificationsPage(in VirtualPageRequest request);
    VirtualNotificationChannelPage listNotificationChannelsPage(in VirtualPageRequest request);
    VirtualJobPage listJobsPage(in VirtualPageRequest request);
    VirtualShortcutPage listShortcutsPage(in VirtualPageRequest request);
    VirtualWidgetPage listAppWidgetsPage(int hostId, in VirtualPageRequest request);
    VirtualSettingPage listSettingsPage(String namespace, in VirtualPageRequest request);
    ParcelFileDescriptor openPageBlob(String blobToken);
    int getAccountVisibility(String name, String type);
    boolean setAccountVisibility(String name, String type, int visibility);
}
