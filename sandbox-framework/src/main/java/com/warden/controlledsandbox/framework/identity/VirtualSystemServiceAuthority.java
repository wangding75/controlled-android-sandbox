package com.warden.controlledsandbox.framework.identity;

import java.util.List;

/** Optional cross-process authority backing Guest-visible virtual system-service state. */
public interface VirtualSystemServiceAuthority extends AutoCloseable {
    record AccountRecord(String name, String type, String password,
                         java.util.Map<String, String> tokens) { }
    record AlarmRecord(String alarmId, long triggerAtMs, long intervalMs, Object token) { }
    record NamespaceMapping(int hostId, boolean created) { }
    record NotificationRecord(int guestId, int hostId, String guestTag, String hostTag,
                              String channelId, String state, Object payload, long updatedAtMs) { }
    record NotificationChannelRecord(String kind, String id, String groupId,
                                     Object payload, long updatedAtMs) { }
    record JobRecord(int guestId, int hostId, String state, String ownerProcessName,
                     long ownerGeneration, Object payload, long updatedAtMs) { }
    record JobParametersRecord(int hostJobId, int guestJobId, String namespace,
                               Object extras, Object transientExtras, Object clipData,
                               int clipGrantFlags, boolean overrideDeadlineExpired,
                               boolean expedited, boolean userInitiated,
                               java.util.List<String> triggeredUris,
                               java.util.List<String> triggeredAuthorities,
                               Object network, int stopReason, int internalStopReason,
                               String debugStopReason, long dispatchToken) { }
    interface JobExecution {
        int guestJobId();
        long generation();
        long dispatchToken();
        boolean active();
        void finish(boolean needsReschedule);
    }
    interface JobExecutionListener {
        boolean onStart(int guestJobId, Object jobPayload, JobParametersRecord parameters,
                        JobExecution execution);
        boolean onStop(int guestJobId, JobParametersRecord parameters);
    }

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

    default NotificationRecord reserveNotification(int guestId, String guestTag, String channelId) { throw new UnsupportedOperationException("notification authority"); }
    default void commitNotification(int guestId, String guestTag, String channelId, Object payload) { throw new UnsupportedOperationException("notification authority"); }
    default boolean removeNotification(int guestId, String guestTag) { return false; }
    default List<NotificationRecord> notifications() { return List.of(); }
    default void upsertNotificationChannel(String kind, String id, String groupId, Object payload) { throw new UnsupportedOperationException("notification channel authority"); }
    default boolean removeNotificationChannel(String kind, String id) { return false; }
    default List<NotificationChannelRecord> notificationChannels() { return List.of(); }

    default JobRecord reserveJob(int guestId, Object payload) { throw new UnsupportedOperationException("job authority"); }
    default void commitJob(int guestId) { throw new UnsupportedOperationException("job authority"); }
    default boolean removeJob(int guestId) { return false; }
    default List<JobRecord> jobs() { return List.of(); }
    default void setJobExecutionListener(JobExecutionListener listener) { }

    NamespaceMapping ensureNamespace(String namespace, int guestId);
    Integer hostIdIfPresent(String namespace, int guestId);
    Integer guestId(String namespace, int hostId);
    Integer removeNamespace(String namespace, int guestId);
    List<Integer> guestIds(String namespace);
    int namespaceSize(String namespace);

    @Override void close();
}
