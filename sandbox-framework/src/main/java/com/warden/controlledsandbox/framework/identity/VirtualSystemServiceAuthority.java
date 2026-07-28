package com.warden.controlledsandbox.framework.identity;

import java.util.List;

/** Optional cross-process authority backing Guest-visible virtual system-service state. */
public interface VirtualSystemServiceAuthority extends AutoCloseable {
    record AccountRecord(String name, String type, String password,
                         java.util.Map<String, String> tokens) { }
    record AlarmRecord(String alarmId, long triggerAtMs, long intervalMs, Object token) { }
    record NamespaceMapping(int hostId, boolean created) { }

    Object clipboard();
    void setClipboard(Object value);
    void clearClipboard();
    void setClipboardChangeListener(Runnable listener);

    List<AccountRecord> accounts(String requestedType);
    boolean addAccount(String name, String type, String password);
    boolean removeAccount(String name, String type);
    void setPassword(String name, String type, String password);
    String password(String name, String type);
    void setToken(String name, String type, String tokenType, String token);
    String token(String name, String type, String tokenType);
    void invalidateToken(String accountType, String token);

    void scheduleAlarm(String alarmId, long triggerAtMs, long intervalMs, Object token, Runnable delivery);
    boolean cancelAlarm(String alarmId);
    List<AlarmRecord> alarms();

    NamespaceMapping ensureNamespace(String namespace, int guestId);
    Integer hostIdIfPresent(String namespace, int guestId);
    Integer guestId(String namespace, int hostId);
    Integer removeNamespace(String namespace, int guestId);
    List<Integer> guestIds(String namespace);
    int namespaceSize(String namespace);

    @Override void close();
}
