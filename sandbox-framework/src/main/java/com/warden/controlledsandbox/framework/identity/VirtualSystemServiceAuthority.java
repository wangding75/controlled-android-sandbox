package com.warden.controlledsandbox.framework.identity;

import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import java.util.List;

/** Optional cross-process authority backing Guest-visible virtual system-service state. */
public interface VirtualSystemServiceAuthority extends AutoCloseable {
    record AccountRecord(String name, String type, String password,
                         java.util.Map<String, String> tokens) { }
    record PendingIntentRecord(String tokenId, String kind, int requestCode, String action,
                               String component, String data, String filterIdentity, int flags, String creatorPackage,
                               int creatorUid, String requiredPermission, String ownerProcessName,
                               long ownerGeneration, String packageRevision, Object payload,
                               int sends, boolean cancelled, long updatedAtMs) { }
    record AlarmRecord(String alarmId, long triggerAtMs, long intervalMs, boolean exact,
                       boolean allowWhileIdle, String deliveryPath, String pendingIntentTokenId,
                       String ownerProcessName, long ownerGeneration, String packageRevision,
                       Object token, int deliveryCount, long updatedAtMs) { }
    record NamespaceMapping(int hostId, boolean created) { }
    record NotificationRecord(int guestId, int hostId, String guestTag, String hostTag,
                              String channelId, String state, String packageRevision,
                              String contentIntentTokenId, String deleteIntentTokenId,
                              java.util.List<String> actionIntentTokenIds, boolean foregroundService,
                              String foregroundServiceKey, Object payload, long updatedAtMs) { }
    record NotificationChannelRecord(String kind, String id, String groupId,
                                     String packageRevision, Object payload, long updatedAtMs) { }
    record JobRecord(int guestId, int hostId, String state, String ownerProcessName,
                     long ownerGeneration, String packageRevision, int requiredNetworkType,
                     boolean requiresCharging, boolean requiresBatteryNotLow,
                     boolean requiresStorageNotLow, boolean requiresDeviceIdle,
                     boolean periodic, long intervalMs, long flexMs, long minimumLatencyMs,
                     long overrideDeadlineMs, boolean expedited, boolean persisted,
                     int backoffPolicy, long initialBackoffMs, int failureCount,
                     long nextRunAtMs, long lastFailureAtMs, Object payload, long updatedAtMs) { }
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

    default VirtualDeviceServiceProfileSnapshot deviceServiceProfile() {
        throw new IllegalStateException("VIRTUAL_DEVICE_PROFILE_NOT_AVAILABLE");
    }
    default VirtualInteractionProfileSnapshot interactionProfile() {
        throw new IllegalStateException("VIRTUAL_INTERACTION_PROFILE_NOT_AVAILABLE");
    }
    default VirtualNetworkServiceProfileSnapshot networkServiceProfile() {
        throw new IllegalStateException("VIRTUAL_NETWORK_PROFILE_NOT_AVAILABLE");
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

    default PendingIntentRecord reservePendingIntent(PendingIntentRecord candidate,
            boolean noCreate, boolean cancelCurrent, boolean updateCurrent) {
        throw new UnsupportedOperationException("pending-intent authority");
    }
    default PendingIntentRecord markPendingIntentSent(String tokenId) {
        throw new UnsupportedOperationException("pending-intent authority");
    }
    default boolean cancelPendingIntent(String tokenId) { return false; }
    default List<PendingIntentRecord> pendingIntents() { return List.of(); }

    void scheduleAlarm(AlarmRecord candidate, Runnable delivery);
    boolean cancelAlarm(String alarmId);
    List<AlarmRecord> alarms();
    default void setRecoveredAlarmDelivery(java.util.function.Function<AlarmRecord, Boolean> delivery) { }

    default NotificationRecord reserveNotification(NotificationRecord candidate) { throw new UnsupportedOperationException("notification authority"); }
    default void commitNotification(NotificationRecord value) { throw new UnsupportedOperationException("notification authority"); }
    default boolean removeNotification(int guestId, String guestTag) { return false; }
    default List<NotificationRecord> notifications() { return List.of(); }
    default void upsertNotificationChannel(NotificationChannelRecord value) { throw new UnsupportedOperationException("notification channel authority"); }
    default boolean removeNotificationChannel(String kind, String id) { return false; }
    default List<NotificationChannelRecord> notificationChannels() { return List.of(); }

    default JobRecord reserveJob(JobRecord candidate) { throw new UnsupportedOperationException("job authority"); }
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
