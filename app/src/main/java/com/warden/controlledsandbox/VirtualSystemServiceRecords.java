package com.warden.controlledsandbox;

import static com.warden.controlledsandbox.VirtualSystemServiceStore.*;

import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/** Durable record types for virtual system-service state. */
final class VirtualSystemServiceRecords {
    private VirtualSystemServiceRecords() { }

    record AccountKey(String name, String type) { }
    static final class AccountRecord {
        String password;
        int visibility = 1;
        final Map<String, String> tokens = new LinkedHashMap<>();
        AccountRecord(String password) { this.password = safe(password); }
    }
    record PendingIntentKey(String kind, int requestCode, String filterIdentity) { }
    static final class PendingIntentRecord {
        final String tokenId; final String kind; final int requestCode; final String action;
        final String component; final String data; final String filterIdentity; int flags; final String creatorPackage;
        final int creatorUid; String requiredPermission; String ownerProcessName;
        long ownerGeneration; final String packageRevision; byte[] payload; int sends;
        boolean cancelled; long updatedAtMs;
        PendingIntentRecord(String tokenId, String kind, int requestCode, String action,
                String component, String data, String filterIdentity, int flags, String creatorPackage, int creatorUid,
                String requiredPermission, String ownerProcessName, long ownerGeneration,
                String packageRevision, byte[] payload, int sends, boolean cancelled, long updatedAtMs) {
            this.tokenId = required(tokenId, "pendingIntentTokenId");
            this.kind = pendingIntentKind(kind);
            if (requestCode < 0 || creatorUid < 0 || ownerGeneration < 0L || sends < 0) {
                throw new IllegalArgumentException("invalid PendingIntent identity");
            }
            this.requestCode = requestCode; this.action = normalize(action);
            this.component = normalize(component); this.data = normalize(data);
            this.filterIdentity = required(filterIdentity, "pendingIntentFilterIdentity"); this.flags = flags;
            this.creatorPackage = required(creatorPackage, "creatorPackage"); this.creatorUid = creatorUid;
            this.requiredPermission = normalize(requiredPermission);
            this.ownerProcessName = required(ownerProcessName, "ownerProcessName");
            this.ownerGeneration = ownerGeneration; this.packageRevision = required(packageRevision, "packageRevision");
            this.payload = boundedPayload(payload, "pendingIntentPayload"); this.sends = sends;
            this.cancelled = cancelled; this.updatedAtMs = Math.max(0L, updatedAtMs);
        }
        PendingIntentKey key() { return new PendingIntentKey(kind, requestCode, filterIdentity); }
    }

    static final class AlarmRecord {
        final String id;
        long triggerAtMs;
        final long intervalMs;
        final boolean exact;
        final boolean allowWhileIdle;
        final String deliveryPath;
        final String pendingIntentTokenId;
        final byte[] tokenPayload;
        final boolean alarmClock;
        final byte[] alarmClockPayload;
        final String ownerProcessName;
        long ownerGeneration;
        final String packageRevision;
        int deliveryCount;
        long updatedAtMs;
        volatile ScheduledFuture<?> future;
        AlarmRecord(String id, long triggerAtMs, long intervalMs, boolean exact,
                    boolean allowWhileIdle, String deliveryPath, String pendingIntentTokenId,
                    byte[] tokenPayload, String ownerProcessName, long ownerGeneration,
                    String packageRevision, int deliveryCount, long updatedAtMs) {
            this(id, triggerAtMs, intervalMs, exact, allowWhileIdle, deliveryPath, pendingIntentTokenId,
                    tokenPayload, false, new byte[0], ownerProcessName, ownerGeneration,
                    packageRevision, deliveryCount, updatedAtMs);
        }
        AlarmRecord(String id, long triggerAtMs, long intervalMs, boolean exact,
                    boolean allowWhileIdle, String deliveryPath, String pendingIntentTokenId,
                    byte[] tokenPayload, boolean alarmClock, byte[] alarmClockPayload,
                    String ownerProcessName, long ownerGeneration,
                    String packageRevision, int deliveryCount, long updatedAtMs) {
            this.id = required(id, "alarmId");
            this.triggerAtMs = Math.max(0L, triggerAtMs);
            this.intervalMs = Math.max(0L, intervalMs);
            this.exact = exact;
            this.allowWhileIdle = allowWhileIdle;
            this.deliveryPath = alarmDeliveryPath(deliveryPath);
            this.pendingIntentTokenId = normalize(pendingIntentTokenId);
            if (VirtualAlarmSnapshot.PENDING_INTENT.equals(this.deliveryPath)
                    && this.pendingIntentTokenId.isEmpty()) {
                throw new IllegalArgumentException("pendingIntentTokenId is required");
            }
            this.tokenPayload = boundedPayload(tokenPayload, "alarmToken");
            this.alarmClock = alarmClock;
            this.alarmClockPayload = boundedPayload(alarmClockPayload, "alarmClockShowIntent");
            this.ownerProcessName = required(ownerProcessName, "ownerProcessName");
            if (ownerGeneration < 0L || deliveryCount < 0) {
                throw new IllegalArgumentException("alarm owner/count must be non-negative");
            }
            this.ownerGeneration = ownerGeneration;
            this.packageRevision = required(packageRevision, "packageRevision");
            this.deliveryCount = deliveryCount;
            this.updatedAtMs = Math.max(0L, updatedAtMs);
        }
    }
    record NotificationKey(int guestId, String guestTag) { }
    static final class NotificationRecord {
        final int guestId; final int hostId; final String guestTag; final String hostTag;
        String channelId; String state; final String packageRevision;
        String contentIntentTokenId; String deleteIntentTokenId;
        List<String> actionIntentTokenIds; boolean foregroundService; String foregroundServiceKey;
        byte[] payload; long updatedAtMs;
        NotificationRecord(int guestId, int hostId, String guestTag, String hostTag,
                           String channelId, String state, String packageRevision,
                           String contentIntentTokenId, String deleteIntentTokenId,
                           List<String> actionIntentTokenIds, boolean foregroundService,
                           String foregroundServiceKey, byte[] payload, long updatedAtMs) {
            if (guestId < 0 || hostId < 0) throw new IllegalArgumentException("notification ids must be non-negative");
            this.guestId = guestId; this.hostId = hostId; this.guestTag = normalizeTag(guestTag);
            this.hostTag = required(hostTag, "hostTag"); this.channelId = normalize(channelId);
            this.state = notificationState(state); this.packageRevision = required(packageRevision, "packageRevision");
            this.contentIntentTokenId = normalize(contentIntentTokenId);
            this.deleteIntentTokenId = normalize(deleteIntentTokenId);
            this.actionIntentTokenIds = boundedTokenIds(actionIntentTokenIds, "notificationActionTokens");
            this.foregroundService = foregroundService;
            this.foregroundServiceKey = optionalIdentity(foregroundServiceKey, "foregroundServiceKey");
            this.payload = boundedPayload(payload, "notificationPayload");
            this.updatedAtMs = Math.max(0L, updatedAtMs);
        }
    }
    static final class NotificationChannelRecord {
        final String kind; final String id; String groupId; final String packageRevision;
        byte[] payload; long updatedAtMs;
        NotificationChannelRecord(String kind, String id, String groupId, String packageRevision,
                                  byte[] payload, long updatedAtMs) {
            this.kind = channelKind(kind); this.id = required(id, "channelId"); this.groupId = normalize(groupId);
            this.packageRevision = required(packageRevision, "packageRevision");
            this.payload = boundedPayload(payload, "notificationChannelPayload");
            this.updatedAtMs = Math.max(0L, updatedAtMs);
        }
    }
    static final class JobRecord {
        final int guestId; final int hostId; String state; final String ownerProcessName;
        long ownerGeneration; final String packageRevision; final int requiredNetworkType;
        final boolean requiresCharging; final boolean requiresBatteryNotLow;
        final boolean requiresStorageNotLow; final boolean requiresDeviceIdle;
        final boolean periodic; final long intervalMs; final long flexMs;
        final long minimumLatencyMs; final long overrideDeadlineMs;
        final boolean expedited; final boolean persisted; final int backoffPolicy;
        final long initialBackoffMs; int failureCount; long nextRunAtMs;
        long lastFailureAtMs; byte[] payload; long updatedAtMs;
        JobRecord(int guestId, int hostId, String state, String ownerProcessName,
                  long ownerGeneration, String packageRevision, int requiredNetworkType,
                  boolean requiresCharging, boolean requiresBatteryNotLow,
                  boolean requiresStorageNotLow, boolean requiresDeviceIdle,
                  boolean periodic, long intervalMs, long flexMs, long minimumLatencyMs,
                  long overrideDeadlineMs, boolean expedited, boolean persisted,
                  int backoffPolicy, long initialBackoffMs, int failureCount,
                  long nextRunAtMs, long lastFailureAtMs, byte[] payload, long updatedAtMs) {
            if (guestId < 0 || hostId < 0 || ownerGeneration < 0L || failureCount < 0
                    || nextRunAtMs < 0L || lastFailureAtMs < 0L) {
                throw new IllegalArgumentException("invalid job identity/state");
            }
            if (requiredNetworkType < VirtualJobSnapshot.NETWORK_NONE
                    || requiredNetworkType > VirtualJobSnapshot.NETWORK_METERED) {
                throw new IllegalArgumentException("invalid job network type");
            }
            if (backoffPolicy != VirtualJobSnapshot.BACKOFF_LINEAR
                    && backoffPolicy != VirtualJobSnapshot.BACKOFF_EXPONENTIAL) {
                throw new IllegalArgumentException("invalid job backoff policy");
            }
            this.guestId = guestId; this.hostId = hostId; this.state = jobState(state);
            this.ownerProcessName = required(ownerProcessName, "ownerProcessName");
            this.ownerGeneration = ownerGeneration; this.packageRevision = required(packageRevision, "packageRevision");
            this.requiredNetworkType = requiredNetworkType; this.requiresCharging = requiresCharging;
            this.requiresBatteryNotLow = requiresBatteryNotLow; this.requiresStorageNotLow = requiresStorageNotLow;
            this.requiresDeviceIdle = requiresDeviceIdle; this.periodic = periodic;
            this.intervalMs = Math.max(0L, intervalMs); this.flexMs = Math.max(0L, Math.min(flexMs, this.intervalMs));
            this.minimumLatencyMs = Math.max(0L, minimumLatencyMs);
            this.overrideDeadlineMs = Math.max(0L, overrideDeadlineMs);
            this.expedited = expedited; this.persisted = persisted; this.backoffPolicy = backoffPolicy;
            this.initialBackoffMs = Math.max(1L, initialBackoffMs); this.failureCount = failureCount;
            this.nextRunAtMs = nextRunAtMs; this.lastFailureAtMs = lastFailureAtMs;
            this.payload = boundedPayload(payload, "jobPayload"); this.updatedAtMs = Math.max(0L, updatedAtMs);
        }
    }
    static final class NamespaceState {
        int next;
        final Map<Integer, Integer> guestToHost = new LinkedHashMap<>();
        final Map<Integer, Integer> hostToGuest = new LinkedHashMap<>();
        NamespaceState(int seed) { next = seed; }
    }
    static final class ScopeState {
        byte[] clipboard = new byte[0];
        final Map<AccountKey, AccountRecord> accounts = new LinkedHashMap<>();
        final Map<String, PendingIntentRecord> pendingIntents = new LinkedHashMap<>();
        final Map<String, AlarmRecord> alarms = new LinkedHashMap<>();
        final Map<String, NamespaceState> namespaces = new LinkedHashMap<>();
        final Map<NotificationKey, NotificationRecord> notifications = new LinkedHashMap<>();
        final Map<String, NotificationChannelRecord> notificationChannels = new LinkedHashMap<>();
        final Map<Integer, JobRecord> jobs = new LinkedHashMap<>();
    }
}
