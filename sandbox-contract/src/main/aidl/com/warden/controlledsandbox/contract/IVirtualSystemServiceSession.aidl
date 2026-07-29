package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;

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

    void close();
}
