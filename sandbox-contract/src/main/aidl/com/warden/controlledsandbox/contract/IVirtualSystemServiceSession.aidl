package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;

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

    void scheduleAlarm(String alarmId, long triggerAtMs, long intervalMs, in byte[] tokenPayload);
    boolean cancelAlarm(String alarmId);
    List<VirtualAlarmSnapshot> listAlarms();

    int ensureNamespace(String namespace, int guestId);
    int hostIdIfPresent(String namespace, int guestId);
    int guestIdForHost(String namespace, int hostId);
    int removeNamespace(String namespace, int guestId);
    int[] listNamespaceGuestIds(String namespace);

    void close();
}
