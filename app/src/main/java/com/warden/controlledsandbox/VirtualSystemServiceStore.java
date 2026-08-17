package com.warden.controlledsandbox;

import static com.warden.controlledsandbox.VirtualSystemServiceRecords.*;
import static com.warden.controlledsandbox.VirtualSystemServiceLimits.*;

import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.internal.DeathRegistrationHelper;
import com.warden.controlledsandbox.contract.IVirtualJobExecution;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAccountSummary;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobWorkItemSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Binder-owned durable state for bounded virtual system services. */
final class VirtualSystemServiceStore implements AutoCloseable {
    interface Client {
        Scope scope();
        String processName();
        long generation();
        IVirtualSystemServiceObserver observer();
        boolean active();
    }
    record Scope(String packageName, int virtualUserId) {
        Scope {
            if (packageName == null || packageName.trim().isEmpty() || virtualUserId < 0) {
                throw new IllegalArgumentException("Invalid virtual system-service scope");
            }
            packageName = packageName.trim();
        }
        String key() { return packageName + "#u" + virtualUserId; }
    }
    private final VirtualSystemServiceStorePersistence persistence;
    private final VirtualSecretCipher secretCipher;
    private final VirtualAccountAuthority accountAuthority = new VirtualAccountAuthority();
    private volatile String maintenanceWarning = "";
    private final Map<Scope, ScopeState> states = new LinkedHashMap<>();
    private final VirtualSystemServiceClientRegistry clientRegistry =
            new VirtualSystemServiceClientRegistry();
    private final Map<Integer, JobExecution> activeJobExecutions = new LinkedHashMap<>();
    private final AtomicLong nextJobDispatchToken = new AtomicLong(1L);
    private long nextPendingIntentToken = 1L;
    private int nextNotificationHostId = 0x51000000;
    private int nextJobHostId = 0x52000000;
    private final ScheduledExecutorService scheduler;
    private final VirtualSystemServiceObserverDispatcher observerDispatcher;
    private boolean closed;

    VirtualSystemServiceStore(File filesDir) {
        this(filesDir, newScheduler());
    }

    VirtualSystemServiceStore(File filesDir, ScheduledExecutorService scheduler) {
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
        observerDispatcher = new VirtualSystemServiceObserverDispatcher(
                scheduler, this::observersForScope, this::recordObserverDispatchFailure);
        secretCipher = new VirtualSecretCipher(
                new File(filesDir, "sandbox-system-services.secrets.key"));
        persistence = new VirtualSystemServiceStorePersistence(
                new File(filesDir, "sandbox-system-services.json"));
        load();
        synchronized (this) {
            for (Map.Entry<Scope, ScopeState> item : states.entrySet()) {
                for (AlarmRecord alarm : item.getValue().alarms.values()) scheduleFuture(item.getKey(), alarm);
            }
        }
    }
    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sandbox-system-service-authority");
            thread.setDaemon(true);
            return thread;
        });
    }
    void register(Client client) {
        List<Client> replaced;
        synchronized (this) {
            requireOpenForMutation();
            replaced = clientRegistry.register(client);
            scheduleClientAlarms(client);
        }
        rescheduleExecutions(replaced);
    }
    void reserveClientRegistration(Client client) {
        synchronized (this) { requireOpenForMutation(); clientRegistry.reserve(client); }
    }
    void commitClientRegistration(Client client) {
        List<Client> replaced;
        synchronized (this) {
            requireOpenForMutation();
            replaced = clientRegistry.commit(client);
            scheduleClientAlarms(client);
        }
        rescheduleExecutions(replaced);
    }
    void unregister(Client client) {
        List<JobExecution> stale = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            clientRegistry.remove(client);
            collectExecutions(client, stale);
        }
        for (JobExecution execution : stale) rescheduleExecution(execution);
    }
    private void scheduleClientAlarms(Client client) {
        for (AlarmRecord alarm : state(client.scope()).alarms.values()) {
            if (alarm.future == null || alarm.future.isCancelled() || alarm.future.isDone()) {
                scheduleFuture(client.scope(), alarm);
            }
        }
    }
    private void rescheduleExecutions(List<Client> replaced) {
        List<JobExecution> stale = new ArrayList<>();
        for (Client client : replaced) collectExecutions(client, stale);
        for (JobExecution execution : stale) rescheduleExecution(execution);
    }
    private void rescheduleExecution(JobExecution execution) {
        try { scheduler.execute(() -> execution.complete(true, true)); }
        catch (RuntimeException rejected) { execution.complete(true, true); }
    }
    private void collectExecutions(Client client, List<JobExecution> out) {
        for (JobExecution execution : activeJobExecutions.values()) {
            if (execution.client == client && execution.active) out.add(execution);
        }
    }

    synchronized byte[] clipboard(Scope scope) { return state(scope).clipboard.clone(); }
    synchronized void setClipboard(Scope scope, byte[] payload) {
        MutationSnapshot before = snapshotMutation(scope);
        state(scope).clipboard = boundedPayload(payload, "clipboard");
        persistOrRestore(scope, before);
        observerDispatcher.dispatch("CLIPBOARD", scope,
                IVirtualSystemServiceObserver::onClipboardChanged);
    }
    synchronized void clearClipboard(Scope scope) {
        MutationSnapshot before = snapshotMutation(scope);
        state(scope).clipboard = new byte[0];
        persistOrRestore(scope, before);
        observerDispatcher.dispatch("CLIPBOARD", scope,
                IVirtualSystemServiceObserver::onClipboardChanged);
    }

    synchronized List<VirtualAccountSnapshot> accounts(Scope scope, String requestedType) {
        return accountAuthority.snapshots(state(scope), requestedType); }
    synchronized List<VirtualAccountSummary> accountSummaries(Scope scope, String requestedType) {
        return accountAuthority.summaries(state(scope), requestedType); }
    synchronized boolean addAccount(Scope scope, String name, String type, String password) {
        MutationSnapshot before = snapshotMutation(scope);
        boolean added = accountAuthority.add(state(scope), name, type, password);
        if (added) persistOrRestore(scope, before);
        return added;
    }
    synchronized boolean removeAccount(Scope scope, String name, String type) {
        MutationSnapshot before = snapshotMutation(scope);
        boolean removed = accountAuthority.remove(state(scope), name, type);
        if (removed) persistOrRestore(scope, before);
        return removed;
    }
    synchronized void setPassword(Scope scope, String name, String type, String password) {
        MutationSnapshot before = snapshotMutation(scope);
        accountAuthority.setPassword(state(scope), name, type, password);
        persistOrRestore(scope, before);
    }
    synchronized String password(Scope scope, String name, String type) {
        return accountAuthority.password(state(scope), name, type); }
    synchronized void setToken(Scope scope, String name, String type,
            String tokenType, String token) {
        MutationSnapshot before = snapshotMutation(scope);
        accountAuthority.setToken(state(scope), name, type, tokenType, token);
        persistOrRestore(scope, before);
    }
    synchronized String token(Scope scope, String name, String type, String tokenType) {
        return accountAuthority.token(state(scope), name, type, tokenType); }
    synchronized void invalidateToken(Scope scope, String accountType, String token) {
        MutationSnapshot before = snapshotMutation(scope);
        if (accountAuthority.invalidateToken(state(scope), accountType, token)) {
            persistOrRestore(scope, before);
        }
    }


    synchronized VirtualPendingIntentSnapshot reservePendingIntent(Scope scope, String processName,
            long generation, String packageRevision, int expectedCreatorUid, VirtualPendingIntentSnapshot candidate,
            boolean noCreate, boolean cancelCurrent, boolean updateCurrent) {
        if (candidate == null) throw new IllegalArgumentException("PendingIntent candidate is required");
        String revision = required(packageRevision, "packageRevision");
        if (!candidate.creatorPackage().equals(scope.packageName())) {
            throw new SecurityException("VIRTUAL_PENDING_INTENT_CREATOR_PACKAGE_MISMATCH");
        }
        if (candidate.creatorUid() != expectedCreatorUid) {
            throw new SecurityException("VIRTUAL_PENDING_INTENT_CREATOR_UID_MISMATCH");
        }
        MutationSnapshot before = snapshotMutation(scope); ScopeState state = state(scope);
        boolean prunedRevision = state.pendingIntents.values()
                .removeIf(value -> !revision.equals(value.packageRevision));
        PendingIntentKey key = pendingIntentKey(candidate);
        PendingIntentRecord existing = findPendingIntent(state, key);
        if (noCreate) {
            if (existing == null) {
                if (prunedRevision) persistOrRestore(scope, before);
                return null;
            }
            boolean changed = rebindPendingIntent(existing, processName, generation);
            if (changed || prunedRevision) persistOrRestore(scope, before);
            return pendingIntentSnapshot(existing);
        }
        if (existing != null && cancelCurrent) {
            existing.cancelled = true; state.pendingIntents.remove(existing.tokenId); existing = null;
        }
        if (existing != null) {
            boolean changed = rebindPendingIntent(existing, processName, generation);
            if (updateCurrent) {
                existing.flags = candidate.flags();
                existing.requiredPermission = normalize(candidate.requiredPermission());
                existing.payload = boundedPayload(candidate.payload(), "pendingIntentPayload");
                existing.updatedAtMs = System.currentTimeMillis(); changed = true;
            }
            if (changed) persistOrRestore(scope, before);
            return pendingIntentSnapshot(existing);
        }
        if (state.pendingIntents.size() >= MAX_PENDING_INTENTS_PER_SCOPE) {
            throw new IllegalStateException("VIRTUAL_PENDING_INTENT_LIMIT_EXCEEDED");
        }
        String tokenId = "pi-" + Long.toUnsignedString(nextPendingIntentToken++, 36);
        PendingIntentRecord created = new PendingIntentRecord(tokenId, candidate.kind(),
                candidate.requestCode(), candidate.action(), candidate.component(), candidate.data(),
                candidate.filterIdentity(), candidate.flags(), candidate.creatorPackage(), candidate.creatorUid(),
                candidate.requiredPermission(), required(processName, "processName"), generation,
                revision, candidate.payload(), 0, false, System.currentTimeMillis());
        state.pendingIntents.put(tokenId, created); persistOrRestore(scope, before);
        return pendingIntentSnapshot(created);
    }
    synchronized VirtualPendingIntentSnapshot markPendingIntentSent(Scope scope, String packageRevision,
            String tokenId) {
        MutationSnapshot before = snapshotMutation(scope); PendingIntentRecord record = requirePendingIntent(scope, packageRevision, tokenId);
        record.sends++; record.updatedAtMs = System.currentTimeMillis();
        boolean oneShot = (record.flags & 0x40000000) != 0;
        List<AlarmRecord> removedAlarms = List.of();
        if (oneShot) {
            record.cancelled = true; state(scope).pendingIntents.remove(record.tokenId);
            removedAlarms = removePendingIntentDependentsLocked(state(scope), record.tokenId);
        }
        persistOrRestore(scope, before);
        for (AlarmRecord alarm : removedAlarms) if (alarm.future != null) alarm.future.cancel(false);
        return pendingIntentSnapshot(record);
    }
    synchronized boolean cancelPendingIntent(Scope scope, String packageRevision, String tokenId) {
        MutationSnapshot before = snapshotMutation(scope); PendingIntentRecord record = state(scope).pendingIntents.get(required(tokenId, "tokenId"));
        if (record == null || !required(packageRevision, "packageRevision").equals(record.packageRevision)) return false;
        record.cancelled = true; state(scope).pendingIntents.remove(record.tokenId);
        List<AlarmRecord> removedAlarms = removePendingIntentDependentsLocked(state(scope), record.tokenId);
        persistOrRestore(scope, before);
        for (AlarmRecord alarm : removedAlarms) if (alarm.future != null) alarm.future.cancel(false);
        return true;
    }
    synchronized List<VirtualPendingIntentSnapshot> pendingIntents(Scope scope, String processName,
            long generation, String packageRevision) {
        MutationSnapshot before = snapshotMutation(scope); String revision = required(packageRevision, "packageRevision");
        List<VirtualPendingIntentSnapshot> out = new ArrayList<>(); boolean changed = false;
        for (PendingIntentRecord record : state(scope).pendingIntents.values()) {
            if (record.cancelled || !revision.equals(record.packageRevision)) continue;
            changed |= rebindPendingIntent(record, processName, generation);
            out.add(pendingIntentSnapshot(record));
        }
        if (changed) persistOrRestore(scope, before);
        out.sort(Comparator.comparing(VirtualPendingIntentSnapshot::tokenId));
        return Collections.unmodifiableList(out);
    }

    synchronized void scheduleAlarm(Scope scope, String processName, long generation,
                                    String alarmId, long triggerAtMs, long intervalMs,
                                    byte[] tokenPayload) {
        scheduleAlarm(scope, processName, generation, "legacy-revision",
                new VirtualAlarmSnapshot(alarmId, triggerAtMs, intervalMs, false, false,
                        VirtualAlarmSnapshot.LISTENER, "", processName, generation,
                        "legacy-revision", tokenPayload, 0, System.currentTimeMillis()));
    }
    synchronized void scheduleAlarm(Scope scope, String processName, long generation,
                                    String packageRevision, VirtualAlarmSnapshot candidate) {
        if (candidate == null) throw new IllegalArgumentException("alarm candidate is required");
        MutationSnapshot before = snapshotMutation(scope); ScopeState state = state(scope);
        String revision = required(packageRevision, "packageRevision");
        List<AlarmRecord> stale = new ArrayList<>();
        for (AlarmRecord value : state.alarms.values()) {
            if (!revision.equals(value.packageRevision)) stale.add(value);
        }
        for (AlarmRecord value : stale) state.alarms.remove(value.id);
        String normalizedId = required(candidate.alarmId(), "alarmId");
        AlarmRecord previous = state.alarms.get(normalizedId);
        if (previous == null && state.alarms.size() >= MAX_ALARMS_PER_SCOPE) {
            throw new IllegalStateException("VIRTUAL_ALARM_LIMIT_EXCEEDED");
        }
        String path = alarmDeliveryPath(candidate.deliveryPath());
        String pendingIntentTokenId = normalize(candidate.pendingIntentTokenId());
        if (VirtualAlarmSnapshot.PENDING_INTENT.equals(path)) {
            requirePendingIntent(scope, revision, pendingIntentTokenId);
        }
        AlarmRecord record = new AlarmRecord(normalizedId, candidate.triggerAtMs(), candidate.intervalMs(),
                candidate.exact(), candidate.allowWhileIdle(), path, pendingIntentTokenId,
                boundedPayload(candidate.tokenPayload(), "alarmToken"),
                candidate.alarmClock(), boundedPayload(candidate.alarmClockPayload(), "alarmClockShowIntent"),
                required(processName, "processName"), generation, revision,
                previous == null ? candidate.deliveryCount() : previous.deliveryCount,
                System.currentTimeMillis());
        state.alarms.put(record.id, record);
        persistOrRestore(scope, before);
        for (AlarmRecord value : stale) if (value.future != null) value.future.cancel(false);
        if (previous != null && previous.future != null) previous.future.cancel(false);
        scheduleFuture(scope, record);
    }
    synchronized boolean cancelAlarm(Scope scope, String alarmId) {
        return cancelAlarm(scope, "legacy-revision", alarmId);
    }
    synchronized boolean cancelAlarm(Scope scope, String packageRevision, String alarmId) {
        MutationSnapshot before = snapshotMutation(scope);
        AlarmRecord existing = state(scope).alarms.get(required(alarmId, "alarmId"));
        if (existing == null || !required(packageRevision, "packageRevision").equals(existing.packageRevision)) {
            return false;
        }
        AlarmRecord removed = state(scope).alarms.remove(existing.id);
        persistOrRestore(scope, before);
        if (removed.future != null) removed.future.cancel(false); return true;
    }
    synchronized List<VirtualAlarmSnapshot> alarms(Scope scope, String processName, long generation) {
        return alarms(scope, processName, generation, "legacy-revision");
    }
    synchronized List<VirtualAlarmSnapshot> alarms(Scope scope, String processName, long generation,
                                                    String packageRevision) {
        MutationSnapshot before = snapshotMutation(scope);
        String owner = required(processName, "processName");
        String revision = required(packageRevision, "packageRevision");
        List<VirtualAlarmSnapshot> out = new ArrayList<>();
        List<AlarmRecord> stale = new ArrayList<>();
        boolean claimed = false;
        for (AlarmRecord alarm : state(scope).alarms.values()) {
            if (!revision.equals(alarm.packageRevision)) { stale.add(alarm); continue; }
            if (!owner.equals(alarm.ownerProcessName)) continue;
            if (alarm.ownerGeneration != generation) {
                alarm.ownerGeneration = generation;
                alarm.updatedAtMs = System.currentTimeMillis();
                claimed = true;
            }
            out.add(alarmSnapshot(alarm));
        }
        for (AlarmRecord value : stale) state(scope).alarms.remove(value.id);
        if (claimed || !stale.isEmpty()) persistOrRestore(scope, before);
        for (AlarmRecord value : stale) if (value.future != null) value.future.cancel(false);
        out.sort(Comparator.comparingLong(VirtualAlarmSnapshot::triggerAtMs).thenComparing(VirtualAlarmSnapshot::alarmId));
        return Collections.unmodifiableList(out);
    }

    synchronized VirtualNotificationSnapshot reserveNotification(Scope scope, long generation,
                                                                  int guestId, String guestTag, String channelId) {
        return reserveNotification(scope, generation, "legacy-revision",
                new VirtualNotificationSnapshot(guestId, 0, guestTag, "", channelId,
                        VirtualNotificationSnapshot.RESERVED, new byte[0], System.currentTimeMillis()));
    }
    synchronized VirtualNotificationSnapshot reserveNotification(Scope scope, long generation,
            String packageRevision, VirtualNotificationSnapshot candidate) {
        if (candidate == null) throw new IllegalArgumentException("notification candidate is required");
        MutationSnapshot before = snapshotMutation(scope); ScopeState state = state(scope);
        String revision = required(packageRevision, "packageRevision");
        pruneNotificationRevisionLocked(state, revision);
        validateNotificationReferences(scope, revision, candidate);
        NotificationKey key = notificationKey(candidate.guestId(), candidate.guestTag());
        NotificationRecord current = state.notifications.get(key);
        if (current == null) {
            if (state.notifications.size() >= MAX_NOTIFICATIONS_PER_SCOPE) {
                throw new IllegalStateException("VIRTUAL_NOTIFICATION_LIMIT_EXCEEDED");
            }
            int hostId = allocateHostId("notification");
            current = new NotificationRecord(candidate.guestId(), hostId, key.guestTag(),
                    hostNotificationTag(scope, key.guestTag(), generation), candidate.channelId(),
                    VirtualNotificationSnapshot.RESERVED, revision,
                    candidate.contentIntentTokenId(), candidate.deleteIntentTokenId(),
                    candidate.actionIntentTokenIds(), candidate.foregroundService(),
                    candidate.foregroundServiceKey(), new byte[0], System.currentTimeMillis());
            state.notifications.put(key, current);
        } else {
            current.channelId = normalize(candidate.channelId());
            current.contentIntentTokenId = normalize(candidate.contentIntentTokenId());
            current.deleteIntentTokenId = normalize(candidate.deleteIntentTokenId());
            current.actionIntentTokenIds = boundedTokenIds(candidate.actionIntentTokenIds(), "notificationActionTokens");
            current.foregroundService = candidate.foregroundService();
            current.foregroundServiceKey = optionalIdentity(candidate.foregroundServiceKey(), "foregroundServiceKey");
            current.state = VirtualNotificationSnapshot.RESERVED;
            current.updatedAtMs = System.currentTimeMillis();
        }
        persistOrRestore(scope, before); return notificationSnapshot(current);
    }
    synchronized void commitNotification(Scope scope, int guestId, String guestTag,
                                         String channelId, byte[] payload) {
        NotificationRecord current = state(scope).notifications.get(notificationKey(guestId, guestTag));
        if (current == null) throw new IllegalStateException("VIRTUAL_NOTIFICATION_RESERVATION_REQUIRED");
        commitNotification(scope, "legacy-revision", new VirtualNotificationSnapshot(guestId,
                current.hostId, guestTag, current.hostTag, channelId, VirtualNotificationSnapshot.ACTIVE,
                "legacy-revision", "", "", List.of(), false, "", payload, System.currentTimeMillis()));
    }
    synchronized void commitNotification(Scope scope, String packageRevision,
                                         VirtualNotificationSnapshot value) {
        if (value == null) throw new IllegalArgumentException("notification value is required");
        MutationSnapshot before = snapshotMutation(scope); String revision = required(packageRevision, "packageRevision");
        validateNotificationReferences(scope, revision, value);
        NotificationRecord record = state(scope).notifications.get(notificationKey(value.guestId(), value.guestTag()));
        if (record == null || !revision.equals(record.packageRevision)) {
            throw new IllegalStateException("VIRTUAL_NOTIFICATION_RESERVATION_REQUIRED");
        }
        record.channelId = normalize(value.channelId());
        record.contentIntentTokenId = normalize(value.contentIntentTokenId());
        record.deleteIntentTokenId = normalize(value.deleteIntentTokenId());
        record.actionIntentTokenIds = boundedTokenIds(value.actionIntentTokenIds(), "notificationActionTokens");
        record.foregroundService = value.foregroundService();
        record.foregroundServiceKey = optionalIdentity(value.foregroundServiceKey(), "foregroundServiceKey");
        record.payload = boundedPayload(value.payload(), "notificationPayload");
        record.state = VirtualNotificationSnapshot.ACTIVE; record.updatedAtMs = System.currentTimeMillis();
        persistOrRestore(scope, before);
    }
    synchronized boolean removeNotification(Scope scope, int guestId, String guestTag) {
        return removeNotification(scope, "legacy-revision", guestId, guestTag);
    }
    synchronized boolean removeNotification(Scope scope, String packageRevision, int guestId, String guestTag) {
        MutationSnapshot before = snapshotMutation(scope); NotificationKey key = notificationKey(guestId, guestTag);
        NotificationRecord existing = state(scope).notifications.get(key);
        if (existing == null || !required(packageRevision, "packageRevision").equals(existing.packageRevision)) return false;
        state(scope).notifications.remove(key); persistOrRestore(scope, before); return true;
    }
    synchronized List<VirtualNotificationSnapshot> notifications(Scope scope) {
        return notifications(scope, "legacy-revision");
    }
    synchronized List<VirtualNotificationSnapshot> notifications(Scope scope, String packageRevision) {
        MutationSnapshot before = snapshotMutation(scope); String revision = required(packageRevision, "packageRevision");
        boolean pruned = pruneNotificationRevisionLocked(state(scope), revision);
        if (pruned) persistOrRestore(scope, before);
        List<VirtualNotificationSnapshot> out = new ArrayList<>();
        for (NotificationRecord record : state(scope).notifications.values()) {
            if (revision.equals(record.packageRevision)) out.add(notificationSnapshot(record));
        }
        out.sort(Comparator.comparingInt(VirtualNotificationSnapshot::guestId)
                .thenComparing(VirtualNotificationSnapshot::guestTag));
        return Collections.unmodifiableList(out);
    }
    synchronized void upsertNotificationChannel(Scope scope, String kind, String id,
                                                String groupId, byte[] payload) {
        upsertNotificationChannel(scope, "legacy-revision", new VirtualNotificationChannelSnapshot(
                kind, id, groupId, "legacy-revision", payload, System.currentTimeMillis()));
    }
    synchronized void upsertNotificationChannel(Scope scope, String packageRevision,
                                                VirtualNotificationChannelSnapshot value) {
        if (value == null) throw new IllegalArgumentException("notification channel is required");
        MutationSnapshot before = snapshotMutation(scope); ScopeState state = state(scope);
        String revision = required(packageRevision, "packageRevision");
        pruneNotificationRevisionLocked(state, revision);
        String key = channelKey(value.kind(), value.id());
        if (!state.notificationChannels.containsKey(key)
                && state.notificationChannels.size() >= MAX_NOTIFICATION_CHANNELS_PER_SCOPE) {
            throw new IllegalStateException("VIRTUAL_NOTIFICATION_CHANNEL_LIMIT_EXCEEDED");
        }
        if (VirtualNotificationChannelSnapshot.CHANNEL.equals(value.kind()) && !value.groupId().isEmpty()) {
            NotificationChannelRecord group = state.notificationChannels.get(
                    channelKey(VirtualNotificationChannelSnapshot.GROUP, value.groupId()));
            if (group == null || !revision.equals(group.packageRevision)) {
                throw new IllegalStateException("VIRTUAL_NOTIFICATION_GROUP_REQUIRED");
            }
        }
        state.notificationChannels.put(key, new NotificationChannelRecord(value.kind(), value.id(), value.groupId(),
                revision, value.payload(), System.currentTimeMillis()));
        persistOrRestore(scope, before);
    }
    synchronized boolean removeNotificationChannel(Scope scope, String kind, String id) {
        return removeNotificationChannel(scope, "legacy-revision", kind, id);
    }
    synchronized boolean removeNotificationChannel(Scope scope, String packageRevision, String kind, String id) {
        MutationSnapshot before = snapshotMutation(scope); ScopeState state = state(scope);
        String revision = required(packageRevision, "packageRevision");
        NotificationChannelRecord existing = state.notificationChannels.get(channelKey(kind, id));
        if (existing == null || !revision.equals(existing.packageRevision)) return false;
        Set<String> channelIds = new LinkedHashSet<>();
        if (VirtualNotificationChannelSnapshot.GROUP.equals(channelKind(kind))) {
            for (NotificationChannelRecord value : state.notificationChannels.values()) {
                if (VirtualNotificationChannelSnapshot.CHANNEL.equals(value.kind)
                        && value.groupId.equals(existing.id) && revision.equals(value.packageRevision)) {
                    channelIds.add(value.id);
                }
            }
            state.notificationChannels.values().removeIf(value -> revision.equals(value.packageRevision)
                    && (value == existing || (VirtualNotificationChannelSnapshot.CHANNEL.equals(value.kind)
                    && value.groupId.equals(existing.id))));
        } else {
            channelIds.add(existing.id); state.notificationChannels.remove(channelKey(kind, id));
        }
        if (!channelIds.isEmpty()) state.notifications.values().removeIf(value -> revision.equals(value.packageRevision)
                && channelIds.contains(value.channelId));
        persistOrRestore(scope, before); return true;
    }
    synchronized List<VirtualNotificationChannelSnapshot> notificationChannels(Scope scope) {
        return notificationChannels(scope, "legacy-revision");
    }
    synchronized List<VirtualNotificationChannelSnapshot> notificationChannels(Scope scope,
                                                                                String packageRevision) {
        MutationSnapshot before = snapshotMutation(scope); String revision = required(packageRevision, "packageRevision");
        boolean pruned = pruneNotificationRevisionLocked(state(scope), revision);
        if (pruned) persistOrRestore(scope, before);
        List<VirtualNotificationChannelSnapshot> out = new ArrayList<>();
        for (NotificationChannelRecord record : state(scope).notificationChannels.values()) {
            if (revision.equals(record.packageRevision)) out.add(new VirtualNotificationChannelSnapshot(
                    record.kind, record.id, record.groupId, record.packageRevision, record.payload, record.updatedAtMs));
        }
        out.sort(Comparator.comparing(VirtualNotificationChannelSnapshot::kind)
                .thenComparing(VirtualNotificationChannelSnapshot::id));
        return Collections.unmodifiableList(out);
    }

    synchronized VirtualJobSnapshot reserveJob(Scope scope, String processName, long generation,
                                               String packageRevision, VirtualJobSnapshot candidate) {
        if (candidate == null) throw new IllegalArgumentException("VIRTUAL_JOB_CANDIDATE_REQUIRED");
        String revision = required(packageRevision, "packageRevision");
        MutationSnapshot before = snapshotMutation(scope); ScopeState state = state(scope);
        pruneJobRevisionLocked(state, revision);
        JobRecord current = state.jobs.get(candidate.guestId());
        int hostId = current == null ? allocateHostId("job") : current.hostId;
        if (current == null && state.jobs.size() >= MAX_JOBS_PER_SCOPE) {
            throw new IllegalStateException("VIRTUAL_JOB_LIMIT_EXCEEDED");
        }
        if (current != null) {
            JobExecution active = activeJobExecutions.get(current.hostId);
            if (active != null && active.active()) throw new IllegalStateException("VIRTUAL_JOB_EXECUTION_ACTIVE");
        }
        long now = System.currentTimeMillis();
        long nextRunAt = candidate.nextRunAtMs() > 0L ? candidate.nextRunAtMs()
                : safeAdd(now, candidate.minimumLatencyMs());
        current = new JobRecord(candidate.guestId(), hostId, VirtualJobSnapshot.RESERVED,
                required(processName, "processName"), generation, revision,
                candidate.requiredNetworkType(), candidate.requiresCharging(),
                candidate.requiresBatteryNotLow(), candidate.requiresStorageNotLow(),
                candidate.requiresDeviceIdle(), candidate.periodic(), candidate.intervalMs(),
                candidate.flexMs(), candidate.minimumLatencyMs(), candidate.overrideDeadlineMs(),
                candidate.expedited(), candidate.persisted(), candidate.backoffPolicy(),
                candidate.initialBackoffMs(), 0, nextRunAt, 0L, candidate.payload(), now);
        state.jobs.put(candidate.guestId(), current);
        persistOrRestore(scope, before); return jobSnapshot(current);
    }
    /** Compatibility helper retained for schema 1-4 tests. */
    synchronized VirtualJobSnapshot reserveJob(Scope scope, String processName, long generation,
                                               int guestId, byte[] payload) {
        return reserveJob(scope, processName, generation, "legacy-revision",
                new VirtualJobSnapshot(guestId, 0, VirtualJobSnapshot.RESERVED, processName,
                        generation, payload, System.currentTimeMillis()));
    }
    synchronized void commitJob(Scope scope, int guestId) {
        MutationSnapshot before = snapshotMutation(scope); JobRecord record = state(scope).jobs.get(guestId);
        if (record == null) throw new IllegalStateException("VIRTUAL_JOB_RESERVATION_REQUIRED");
        record.state = VirtualJobSnapshot.SCHEDULED;
        if (record.nextRunAtMs == 0L) record.nextRunAtMs = safeAdd(System.currentTimeMillis(), record.minimumLatencyMs);
        record.updatedAtMs = System.currentTimeMillis();
        persistOrRestore(scope, before);
    }
    boolean removeJob(Scope scope, int guestId) {
        JobExecution execution;
        synchronized (this) {
            JobRecord record = state(scope).jobs.get(guestId);
            execution = record == null ? null : activeJobExecutions.get(record.hostId);
        }
        if (execution != null) {
            execution.cancelWithoutHost(false);
            return true;
        }
        synchronized (this) {
            MutationSnapshot before = snapshotMutation(scope); boolean removed = state(scope).jobs.remove(guestId) != null;
            if (removed) persistOrRestore(scope, before); return removed;
        }
    }
    synchronized List<VirtualJobSnapshot> jobs(Scope scope, String processName, long generation,
                                                String packageRevision) {
        MutationSnapshot before = snapshotMutation(scope); String revision = required(packageRevision, "packageRevision");
        boolean changed = pruneJobRevisionLocked(state(scope), revision); List<VirtualJobSnapshot> out = new ArrayList<>();
        for (JobRecord record : state(scope).jobs.values()) {
            if (!revision.equals(record.packageRevision)) continue;
            if (record.ownerProcessName.equals(processName) && record.ownerGeneration != generation) {
                JobExecution active = activeJobExecutions.get(record.hostId);
                if (active != null && active.active()) continue;
                record.ownerGeneration = generation;
                if (VirtualJobSnapshot.DISPATCHING.equals(record.state)
                        || VirtualJobSnapshot.RUNNING.equals(record.state)) {
                    record.state = VirtualJobSnapshot.SCHEDULED;
                    record.nextRunAtMs = Math.max(record.nextRunAtMs, System.currentTimeMillis());
                }
                record.updatedAtMs = System.currentTimeMillis(); changed = true;
            }
            out.add(jobSnapshot(record));
        }
        if (changed) persistOrRestore(scope, before);
        out.sort(Comparator.comparingInt(VirtualJobSnapshot::guestId));
        return Collections.unmodifiableList(out);
    }
    synchronized List<VirtualJobSnapshot> jobs(Scope scope, String processName, long generation) {
        return jobs(scope, processName, generation, "legacy-revision");
    }

    boolean startJob(VirtualJobParametersSnapshot parameters, IHostJobCallback hostCallback,
                     int ownerUid) {
        if (parameters == null || hostCallback == null || hostCallback.asBinder() == null
                || !hostCallback.asBinder().isBinderAlive()) return false;
        JobExecution execution;
        synchronized (this) {
            LocatedJob located = findJobByHost(parameters.hostJobId());
            if (located == null || !VirtualJobSnapshot.SCHEDULED.equals(located.job.state)
                    || activeJobExecutions.containsKey(parameters.hostJobId())) return false;
            long now = System.currentTimeMillis();
            long deadlineAt = located.job.overrideDeadlineMs == 0L ? Long.MAX_VALUE
                    : safeAdd(located.job.updatedAtMs, located.job.overrideDeadlineMs);
            if (now < located.job.nextRunAtMs && now < deadlineAt) return false;
            Client client = matchingClient(located.scope, located.job);
            if (client == null) return false;
            MutationSnapshot before = snapshotMutation(located.scope);
            located.job.state = VirtualJobSnapshot.DISPATCHING;
            located.job.updatedAtMs = System.currentTimeMillis();
            persistOrRestore(located.scope, before);
            long token = positiveToken();
            execution = new JobExecution(located.scope, located.job, client, ownerUid,
                    parameters.forGuest(located.job.guestId, token), hostCallback, token);
            activeJobExecutions.put(located.job.hostId, execution);
            try {
                boolean linked = execution.deathRegistration.linkAfterReservation();
                if (!linked || activeJobExecutions.get(located.job.hostId) != execution
                        || !execution.deathRegistration.linkedAndAlive()) {
                    execution.rollbackDeathLinkLocked();
                    return false;
                }
            } catch (RemoteException error) {
                try {
                    execution.rollbackDeathLinkLocked();
                } catch (RuntimeException persistenceFailure) {
                    persistenceFailure.addSuppressed(error);
                    maintenanceWarning = "VIRTUAL_JOB_LINK_DEATH_ROLLBACK_FAILED:"
                            + persistenceFailure.getClass().getSimpleName();
                    throw persistenceFailure;
                }
                return false;
            } catch (RuntimeException | Error error) {
                execution.rollbackDeathLinkLocked();
                throw error;
            }
        }
        boolean accepted;
        try {
            accepted = execution.client.observer().onJobStart(execution.job.guestId,
                    execution.job.payload.clone(), execution.parameters, execution);
        } catch (Exception error) { accepted = false; }
        if (!accepted) {
            execution.rejectStart();
            return false;
        }
        synchronized (this) {
            if (!execution.active) return true;
            LocatedJob current = findJobByHost(execution.job.hostId);
            if (current == null || current.job != execution.job) {
                execution.invalidateLocked(); return false;
            }
            MutationSnapshot before = snapshotMutation(current.scope);
            current.job.state = VirtualJobSnapshot.RUNNING;
            current.job.updatedAtMs = System.currentTimeMillis();
            try { persistOrRestore(current.scope, before); }
            catch (RuntimeException error) {
                execution.invalidateLocked();
                MutationSnapshot compensation = snapshotMutation(current.scope);
                current.job.state = VirtualJobSnapshot.SCHEDULED;
                current.job.updatedAtMs = System.currentTimeMillis();
                try {
                    persistOrRestore(current.scope, compensation);
                } catch (RuntimeException compensationFailure) {
                    error.addSuppressed(compensationFailure);
                    maintenanceWarning = "VIRTUAL_JOB_START_ROLLBACK_FAILED:"
                            + compensationFailure.getClass().getSimpleName();
                }
                throw error;
            }
            execution.timeout = scheduler.schedule(execution::timeout,
                    JOB_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    boolean stopJob(int hostJobId, int stopReason, int internalStopReason,
                    String debugStopReason) {
        JobExecution execution;
        synchronized (this) { execution = activeJobExecutions.get(hostJobId); }
        if (execution == null || !execution.active()) return true;
        // JobParameters.dequeueWork() has a framework-owned successful terminal path: when the
        // queue is empty and all dequeued work is complete, Android calls onStopJob() with
        // INTERNAL_STOP_REASON_SUCCESSFUL_FINISH instead of expecting jobFinished(). Do not
        // synthesize Guest onStopJob() for that path; it is a successful completion, not a
        // preemption, and the Guest may still be unwinding its final dequeue call.
        if (internalStopReason == 10
                || "last work dequeued".equalsIgnoreCase(
                        debugStopReason == null ? "" : debugStopReason.trim())) {
            execution.complete(false, false);
            return false;
        }
        boolean reschedule = true;
        try {
            reschedule = execution.client.observer().onJobStop(execution.job.guestId,
                    execution.parameters.withStopReason(stopReason, internalStopReason, debugStopReason));
        } catch (Exception ignored) { reschedule = true; }
        execution.complete(reschedule, false);
        return reschedule;
    }

    private synchronized LocatedJob findJobByHost(int hostId) {
        for (Map.Entry<Scope, ScopeState> item : states.entrySet()) {
            for (JobRecord job : item.getValue().jobs.values()) {
                if (job.hostId == hostId) return new LocatedJob(item.getKey(), job);
            }
        }
        return null;
    }
    private synchronized Client matchingClient(Scope scope, JobRecord job) {
        Client match = null;
        for (Client client : clientRegistry.snapshot()) {
            if (!client.active() || client.observer() == null || !client.scope().equals(scope)
                    || !client.processName().equals(job.ownerProcessName)
                    || client.generation() != job.ownerGeneration) continue;
            if (match != null) return null;
            match = client;
        }
        return match;
    }
    private long positiveToken() {
        long value = nextJobDispatchToken.getAndIncrement();
        if (value > 0L) return value;
        synchronized (nextJobDispatchToken) {
            nextJobDispatchToken.set(2L); return 1L;
        }
    }
    private record LocatedJob(Scope scope, JobRecord job) { }

    private final class JobExecution extends IVirtualJobExecution.Stub implements IBinder.DeathRecipient {
        final Scope scope; final JobRecord job; final Client client; final int ownerUid;
        final VirtualJobParametersSnapshot parameters; final IHostJobCallback hostCallback;
        final long token; final DeathRegistrationHelper deathRegistration;
        volatile boolean active = true; volatile ScheduledFuture<?> timeout;
        JobExecution(Scope scope, JobRecord job, Client client, int ownerUid,
                     VirtualJobParametersSnapshot parameters, IHostJobCallback hostCallback,
                     long token) {
            this.scope = scope; this.job = job; this.client = client; this.ownerUid = ownerUid;
            this.parameters = parameters; this.hostCallback = hostCallback; this.token = token;
            deathRegistration = new DeathRegistrationHelper(hostCallback.asBinder(), this::binderDied);
        }
        @Override public int guestJobId() { return job.guestId; }
        @Override public long generation() { return job.ownerGeneration; }
        @Override public long dispatchToken() { return token; }
        @Override public boolean isActive() { return active(); }
        @Override public void finish(boolean needsReschedule) {
            if (Binder.getCallingUid() != ownerUid) throw new SecurityException("JOB_EXECUTION_UID_MISMATCH");
            complete(needsReschedule, true);
        }
        @Override public VirtualJobWorkItemSnapshot dequeueWork() {
            if (Binder.getCallingUid() != ownerUid) {
                throw new SecurityException("JOB_EXECUTION_UID_MISMATCH");
            }
            if (!active()) return null;
            try {
                return hostCallback.dequeueHostWork(job.hostId);
            } catch (RemoteException error) {
                cancelWithoutHost(true);
                return null;
            }
        }
        @Override public boolean completeWork(int workId) {
            if (Binder.getCallingUid() != ownerUid) {
                throw new SecurityException("JOB_EXECUTION_UID_MISMATCH");
            }
            if (workId < 0 || !active()) return false;
            try {
                return hostCallback.completeHostWork(job.hostId, workId);
            } catch (RemoteException error) {
                cancelWithoutHost(true);
                return false;
            }
        }
        @Override public void binderDied() { cancelWithoutHost(true); }
        boolean active() {
            synchronized (VirtualSystemServiceStore.this) {
                return active && activeJobExecutions.get(job.hostId) == this;
            }
        }
        void rejectStart() { complete(true, false); }
        void timeout() { complete(true, true); }
        void cancelWithoutHost(boolean reschedule) { complete(reschedule, false); }
        void rollbackDeathLinkLocked() {
            if (!active || activeJobExecutions.get(job.hostId) != this) return;
            MutationSnapshot compensation = snapshotMutation(scope);
            job.state = VirtualJobSnapshot.SCHEDULED;
            job.updatedAtMs = System.currentTimeMillis();
            try {
                persistOrRestore(scope, compensation);
            } finally {
                invalidateLocked();
            }
        }
        void complete(boolean needsReschedule, boolean notifyHost) {
            boolean callHost = false;
            synchronized (VirtualSystemServiceStore.this) {
                if (!active()) return;
                MutationSnapshot before = snapshotMutation(scope);
                long now = System.currentTimeMillis();
                if (needsReschedule) {
                    job.failureCount = Math.min(31, job.failureCount + 1);
                    job.lastFailureAtMs = now;
                    job.nextRunAtMs = safeAdd(now, retryDelay(job));
                    job.state = VirtualJobSnapshot.SCHEDULED;
                    job.updatedAtMs = now;
                } else if (job.periodic) {
                    job.failureCount = 0;
                    job.lastFailureAtMs = 0L;
                    job.nextRunAtMs = safeAdd(now, job.intervalMs);
                    job.state = VirtualJobSnapshot.SCHEDULED;
                    job.updatedAtMs = now;
                } else {
                    state(scope).jobs.remove(job.guestId);
                }
                persistOrRestore(scope, before);
                invalidateLocked(); callHost = notifyHost;
            }
            if (callHost) {
                try { hostCallback.finishHostJob(job.hostId, needsReschedule); }
                catch (Exception ignored) { }
            }
        }
        void invalidateLocked() {
            if (!active) return; active = false;
            activeJobExecutions.remove(job.hostId, this);
            ScheduledFuture<?> value = timeout; if (value != null) value.cancel(false);
            deathRegistration.close();
        }
    }

    synchronized String maintenanceWarning() { return maintenanceWarning; }

    void deleteScopeBestEffort(Scope scope) {
        List<JobExecution> active = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            for (JobExecution execution : activeJobExecutions.values()) {
                if (execution.scope.equals(scope)) active.add(execution);
            }
        }
        String executionWarning = "";
        for (JobExecution execution : active) {
            try { execution.complete(false, true); }
            catch (RuntimeException error) {
                synchronized (this) { execution.invalidateLocked(); }
                executionWarning = "VIRTUAL_JOB_SCOPE_CANCEL_FAILED:" + error.getClass().getSimpleName();
            }
        }
        synchronized (this) {
            if (closed) return;
            ScopeState removed = states.remove(scope);
            if (removed == null) return;
            for (AlarmRecord alarm : removed.alarms.values()) if (alarm.future != null) alarm.future.cancel(false);
            try {
                persist();
                maintenanceWarning = executionWarning;
            } catch (RuntimeException error) {
                states.put(scope, removed);
                for (AlarmRecord alarm : removed.alarms.values()) scheduleFuture(scope, alarm);
                maintenanceWarning = "VIRTUAL_SYSTEM_SERVICE_SCOPE_CLEANUP_FAILED:" + error.getClass().getSimpleName();
            }
        }
    }

    synchronized int ensureNamespace(Scope scope, String namespace, int guestId) {
        MutationSnapshot before = snapshotMutation(scope);
        NamespaceState state = namespace(scope, namespace); Integer existing = state.guestToHost.get(guestId);
        if (existing != null) return existing;
        if (state.guestToHost.size() >= MAX_NAMESPACE_MAPPINGS) {
            throw new IllegalStateException("VIRTUAL_NAMESPACE_LIMIT_EXCEEDED");
        }
        int candidate = allocateHostId(normalizeRequired(namespace, "namespace"));
        state.next = Math.max(state.next, candidate + 1);
        state.guestToHost.put(guestId, candidate); state.hostToGuest.put(candidate, guestId);
        persistOrRestore(scope, before); return candidate;
    }
    synchronized int hostIdIfPresent(Scope scope, String namespace, int guestId) {
        Integer value = namespace(scope, namespace).guestToHost.get(guestId); return value == null ? -1 : value;
    }
    synchronized int guestIdForHost(Scope scope, String namespace, int hostId) {
        Integer value = namespace(scope, namespace).hostToGuest.get(hostId); return value == null ? -1 : value;
    }
    synchronized int removeNamespace(Scope scope, String namespace, int guestId) {
        MutationSnapshot before = snapshotMutation(scope);
        NamespaceState state = namespace(scope, namespace); Integer host = state.guestToHost.remove(guestId);
        if (host == null) return -1; state.hostToGuest.remove(host);
        persistOrRestore(scope, before); return host;
    }
    synchronized int[] namespaceGuestIds(Scope scope, String namespace) {
        List<Integer> values = new ArrayList<>(namespace(scope, namespace).guestToHost.keySet());
        Collections.sort(values); int[] out = new int[values.size()];
        for (int index = 0; index < values.size(); index++) out[index] = values.get(index); return out;
    }

    private synchronized void scheduleFuture(Scope scope, AlarmRecord alarm) {
        if (alarm.future != null) alarm.future.cancel(false);
        long delay = Math.max(0L, alarm.triggerAtMs - System.currentTimeMillis());
        alarm.future = scheduler.schedule(() -> fire(scope, alarm.id), delay, TimeUnit.MILLISECONDS);
    }
    private void fire(Scope scope, String alarmId) {
        AlarmRecord alarm;
        List<IVirtualSystemServiceObserver> observers = new ArrayList<>();
        synchronized (this) {
            alarm = state(scope).alarms.get(alarmId);
            if (alarm == null) return;
            for (Client client : clientRegistry.snapshot()) {
                if (client.active() && client.scope().equals(scope)
                        && client.processName().equals(alarm.ownerProcessName)
                        && client.generation() == alarm.ownerGeneration
                        && client.observer() != null) {
                    observers.add(client.observer());
                }
            }
        }
        boolean delivered = false;
        for (IVirtualSystemServiceObserver observer : observers) {
            try { observer.onAlarm(alarmSnapshot(alarm)); delivered = true; } catch (Exception ignored) { }
        }
        synchronized (this) {
            AlarmRecord current = state(scope).alarms.get(alarmId);
            if (current != alarm) return;
            MutationSnapshot before = snapshotMutation(scope);
            boolean reschedule = false;
            if (!delivered) {
                alarm.triggerAtMs = System.currentTimeMillis() + RETRY_WITHOUT_CLIENT_MS;
                reschedule = true;
            } else if (alarm.intervalMs > 0L) {
                alarm.deliveryCount++; alarm.updatedAtMs = System.currentTimeMillis();
                long next = alarm.triggerAtMs + alarm.intervalMs;
                long now = System.currentTimeMillis();
                while (next <= now) next += alarm.intervalMs;
                alarm.triggerAtMs = next;
                reschedule = true;
            } else {
                alarm.deliveryCount++; alarm.updatedAtMs = System.currentTimeMillis();
                state(scope).alarms.remove(alarmId);
            }
            try {
                persistOrRestore(scope, before);
            } catch (RuntimeException error) {
                maintenanceWarning = "VIRTUAL_ALARM_PERSIST_FAILED:"
                        + error.getClass().getSimpleName();
                AlarmRecord restored = state(scope).alarms.get(alarmId);
                if (restored != null) {
                    restored.triggerAtMs = System.currentTimeMillis() + RETRY_WITHOUT_CLIENT_MS;
                    scheduleFuture(scope, restored);
                }
                return;
            }
            if (reschedule) scheduleFuture(scope, alarm);
        }
    }
    void notifyDeviceProfileChanged(Scope scope, long policyVersion) {
        if (policyVersion < 1L) return;
        observerDispatcher.dispatch("DEVICE_PROFILE", scope,
                observer -> observer.onDeviceServiceProfileChanged(policyVersion));
    }
    void notifyInteractionProfileChanged(Scope scope, long policyVersion) {
        if (policyVersion < 1L) return;
        observerDispatcher.dispatch("INTERACTION_PROFILE", scope,
                observer -> observer.onInteractionProfileChanged(policyVersion));
    }
    void notifyNetworkProfileChanged(Scope scope, long policyVersion) {
        if (policyVersion < 1L) return;
        observerDispatcher.dispatch("NETWORK_PROFILE", scope,
                observer -> observer.onNetworkServiceProfileChanged(policyVersion));
    }
    void notifyApplicationEnvironmentProfileChanged(Scope scope, long policyVersion) {
        if (policyVersion < 1L) return;
        observerDispatcher.dispatch("APPLICATION_ENVIRONMENT_PROFILE", scope,
                observer -> observer.onApplicationEnvironmentProfileChanged(policyVersion));
    }
    void notifyCompatibilityProfileChanged(Scope scope, long policyVersion) {
        if (policyVersion < 1L) return;
        observerDispatcher.dispatch("COMPATIBILITY_PROFILE", scope,
                observer -> observer.onCompatibilityProfileChanged(policyVersion));
    }
    void notifyPolicyServicesProfileChanged(Scope scope, long policyVersion) {
        if (policyVersion < 1L) return;
        observerDispatcher.dispatch("POLICY_SERVICES_PROFILE", scope,
                observer -> observer.onPolicyServicesProfileChanged(policyVersion));
    }
    void notifyMediaCommunicationProfileChanged(Scope scope, long policyVersion) {
        if (policyVersion < 1L) return;
        observerDispatcher.dispatch("MEDIA_COMMUNICATION_PROFILE", scope,
                observer -> observer.onMediaCommunicationProfileChanged(policyVersion));
    }
    void notifyPeripheralServicesProfileChanged(Scope scope, long policyVersion) {
        if (policyVersion < 1L) return;
        observerDispatcher.dispatch("PERIPHERAL_SERVICES_PROFILE", scope,
                observer -> observer.onPeripheralServicesProfileChanged(policyVersion));
    }
    void notifyPrivilegedServicesProfileChanged(Scope scope, long policyVersion) {
        if (policyVersion < 1L) return;
        observerDispatcher.dispatch("PRIVILEGED_SERVICES_PROFILE", scope,
                observer -> observer.onPrivilegedServicesProfileChanged(policyVersion));
    }
    void notifyApplicationEnvironmentDataChanged(Scope scope, String domain, String key) {
        String normalizedDomain = domain == null ? "" : domain;
        String normalizedKey = key == null ? "" : key;
        observerDispatcher.dispatch("APPLICATION_ENVIRONMENT_DATA", scope,
                observer -> observer.onApplicationEnvironmentDataChanged(
                        normalizedDomain, normalizedKey));
    }
    private synchronized List<IVirtualSystemServiceObserver> observersForScope(Scope scope) {
        if (closed) return List.of();
        List<IVirtualSystemServiceObserver> observers = new ArrayList<>();
        for (Client client : clientRegistry.snapshot()) {
            if (client.active() && client.scope().equals(scope) && client.observer() != null) {
                observers.add(client.observer());
            }
        }
        return observers;
    }
    private synchronized void recordObserverDispatchFailure(String warning) {
        if (!closed) maintenanceWarning = warning;
    }

    private ScopeState snapshotScope(Scope scope) {
        ScopeState current = states.get(scope);
        if (current == null) return null;
        ScopeState copy = new ScopeState();
        copy.clipboard = current.clipboard.clone();
        for (Map.Entry<AccountKey, AccountRecord> item : current.accounts.entrySet()) {
            AccountRecord account = new AccountRecord(item.getValue().password);
            account.tokens.putAll(item.getValue().tokens);
            copy.accounts.put(item.getKey(), account);
        }
        for (Map.Entry<String, PendingIntentRecord> item : current.pendingIntents.entrySet()) {
            PendingIntentRecord value = item.getValue();
            copy.pendingIntents.put(item.getKey(), new PendingIntentRecord(value.tokenId, value.kind,
                    value.requestCode, value.action, value.component, value.data, value.filterIdentity, value.flags,
                    value.creatorPackage, value.creatorUid, value.requiredPermission,
                    value.ownerProcessName, value.ownerGeneration, value.packageRevision,
                    value.payload, value.sends, value.cancelled, value.updatedAtMs));
        }
        for (Map.Entry<String, AlarmRecord> item : current.alarms.entrySet()) {
            AlarmRecord alarm = item.getValue();
            AlarmRecord alarmCopy = new AlarmRecord(alarm.id, alarm.triggerAtMs, alarm.intervalMs,
                    alarm.exact, alarm.allowWhileIdle, alarm.deliveryPath, alarm.pendingIntentTokenId,
                    alarm.tokenPayload, alarm.alarmClock, alarm.alarmClockPayload,
                    alarm.ownerProcessName, alarm.ownerGeneration, alarm.packageRevision,
                    alarm.deliveryCount, alarm.updatedAtMs);
            alarmCopy.future = alarm.future;
            copy.alarms.put(item.getKey(), alarmCopy);
        }
        for (Map.Entry<String, NamespaceState> item : current.namespaces.entrySet()) {
            NamespaceState namespace = new NamespaceState(item.getValue().next);
            namespace.guestToHost.putAll(item.getValue().guestToHost);
            namespace.hostToGuest.putAll(item.getValue().hostToGuest);
            copy.namespaces.put(item.getKey(), namespace);
        }
        for (Map.Entry<NotificationKey, NotificationRecord> item : current.notifications.entrySet()) {
            NotificationRecord value = item.getValue();
            copy.notifications.put(item.getKey(), new NotificationRecord(value.guestId, value.hostId,
                    value.guestTag, value.hostTag, value.channelId, value.state, value.packageRevision,
                    value.contentIntentTokenId, value.deleteIntentTokenId, value.actionIntentTokenIds,
                    value.foregroundService, value.foregroundServiceKey, value.payload, value.updatedAtMs));
        }
        for (Map.Entry<String, NotificationChannelRecord> item : current.notificationChannels.entrySet()) {
            NotificationChannelRecord value = item.getValue();
            copy.notificationChannels.put(item.getKey(), new NotificationChannelRecord(value.kind, value.id,
                    value.groupId, value.packageRevision, value.payload, value.updatedAtMs));
        }
        for (Map.Entry<Integer, JobRecord> item : current.jobs.entrySet()) {
            JobRecord value = item.getValue();
            copy.jobs.put(item.getKey(), new JobRecord(value.guestId, value.hostId, value.state,
                    value.ownerProcessName, value.ownerGeneration, value.packageRevision,
                    value.requiredNetworkType, value.requiresCharging, value.requiresBatteryNotLow,
                    value.requiresStorageNotLow, value.requiresDeviceIdle, value.periodic,
                    value.intervalMs, value.flexMs, value.minimumLatencyMs, value.overrideDeadlineMs,
                    value.expedited, value.persisted, value.backoffPolicy, value.initialBackoffMs,
                    value.failureCount, value.nextRunAtMs, value.lastFailureAtMs,
                    value.payload, value.updatedAtMs));
        }
        return copy;
    }
    private MutationSnapshot snapshotMutation(Scope scope) {
        requireOpenForMutation();
        return new MutationSnapshot(snapshotScope(scope), nextPendingIntentToken,
                nextNotificationHostId, nextJobHostId);
    }

    private void persistOrRestore(Scope scope, MutationSnapshot before) {
        try { persist(); }
        catch (RuntimeException error) {
            if (before.scopeState() == null) states.remove(scope);
            else states.put(scope, before.scopeState());
            nextPendingIntentToken = before.nextPendingIntentToken();
            nextNotificationHostId = before.nextNotificationHostId();
            nextJobHostId = before.nextJobHostId();
            throw error;
        }
    }

    private record MutationSnapshot(
            ScopeState scopeState,
            long nextPendingIntentToken,
            int nextNotificationHostId,
            int nextJobHostId) { }

    private void requireOpenForMutation() {
        if (closed) throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_STORE_CLOSED");
    }
    private ScopeState state(Scope scope) {
        ScopeState existing = states.get(scope);
        if (existing != null) return existing;
        requireOpenForMutation();
        ScopeState created = new ScopeState();
        states.put(scope, created);
        return created;
    }
    private NamespaceState namespace(Scope scope, String namespace) {
        String normalized = normalizeRequired(namespace, "namespace");
        ScopeState owner = state(scope);
        NamespaceState existing = owner.namespaces.get(normalized);
        if (existing != null) return existing;
        requireOpenForMutation();
        int seed = switch (normalized) { case "notification" -> 0x51000000; case "job" -> 0x52000000; default -> 0x53000000; };
        NamespaceState created = new NamespaceState(seed);
        owner.namespaces.put(normalized, created);
        return created;
    }
    static AccountKey accountKey(String name, String type) {
        return new AccountKey(required(name, "name"), required(type, "type"));
    }

    private void load() {
        try {
            String payload = persistence.readPayload();
            if (payload == null) {
                secretCipher.retireLegacyKey();
                return;
            }
            VirtualSystemServiceStoreCodec.Decoded decoded =
                    VirtualSystemServiceStoreCodec.decode(payload, secretCipher);
            states.clear();
            states.putAll(decoded.states());
            nextNotificationHostId = decoded.nextNotificationHostId();
            nextJobHostId = decoded.nextJobHostId();
            nextPendingIntentToken = decoded.nextPendingIntentToken();
            removeStaleReservations();
            if (decoded.requiresSecretMigration() || secretCipher.hasLegacyKey()) {
                persist();
                secretCipher.retireLegacyKey();
            }
        } catch (RuntimeException error) {
            states.clear();
            nextNotificationHostId = 0x51000000;
            nextJobHostId = 0x52000000;
            nextPendingIntentToken = 1L;
            persistence.quarantine();
            maintenanceWarning = error.getMessage() == null
                    ? "VIRTUAL_SYSTEM_SERVICE_STORE_CORRUPT"
                    : error.getMessage();
        }
    }
    private synchronized void persist() {
        persistence.writePayload(VirtualSystemServiceStoreCodec.encode(
                states, nextNotificationHostId, nextJobHostId, nextPendingIntentToken, secretCipher));
    }


    private synchronized int allocateHostId(String namespace) {
        int candidate;
        if ("notification".equals(namespace)) {
            do { candidate = nextNotificationHostId++; } while (hostIdInUse(namespace, candidate));
            return candidate;
        }
        if ("job".equals(namespace)) {
            do { candidate = nextJobHostId++; } while (hostIdInUse(namespace, candidate));
            return candidate;
        }
        int next = 0x53000000;
        do { candidate = next++; } while (hostIdInUse(namespace, candidate));
        return candidate;
    }
    private boolean hostIdInUse(String namespace, int hostId) {
        for (ScopeState state : states.values()) {
            NamespaceState value = state.namespaces.get(namespace);
            if (value != null && value.hostToGuest.containsKey(hostId)) return true;
            if ("notification".equals(namespace)) {
                for (NotificationRecord record : state.notifications.values()) if (record.hostId == hostId) return true;
            }
            if ("job".equals(namespace)) {
                for (JobRecord record : state.jobs.values()) if (record.hostId == hostId) return true;
            }
        }
        return false;
    }
    private void removeStaleReservations() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        for (ScopeState state : states.values()) {
            state.notifications.values().removeIf(value -> VirtualNotificationSnapshot.RESERVED.equals(value.state)
                    && value.updatedAtMs < cutoff);
            state.jobs.values().removeIf(value -> VirtualJobSnapshot.RESERVED.equals(value.state)
                    && value.updatedAtMs < cutoff);
            for (JobRecord value : state.jobs.values()) {
                if (VirtualJobSnapshot.DISPATCHING.equals(value.state)
                        || VirtualJobSnapshot.RUNNING.equals(value.state)) {
                    value.state = VirtualJobSnapshot.SCHEDULED;
                    value.updatedAtMs = System.currentTimeMillis();
                }
            }
        }
    }
    private static PendingIntentKey pendingIntentKey(VirtualPendingIntentSnapshot value) {
        return new PendingIntentKey(pendingIntentKind(value.kind()), value.requestCode(),
                required(value.filterIdentity(), "pendingIntentFilterIdentity"));
    }
    private static PendingIntentRecord findPendingIntent(ScopeState state, PendingIntentKey key) {
        for (PendingIntentRecord value : state.pendingIntents.values()) {
            if (!value.cancelled && value.key().equals(key)) return value;
        }
        return null;
    }
    private PendingIntentRecord requirePendingIntent(Scope scope, String packageRevision, String tokenId) {
        PendingIntentRecord record = state(scope).pendingIntents.get(required(tokenId, "tokenId"));
        if (record == null || record.cancelled) throw new IllegalStateException("VIRTUAL_PENDING_INTENT_CANCELLED");
        if (!required(packageRevision, "packageRevision").equals(record.packageRevision)) {
            throw new SecurityException("VIRTUAL_PENDING_INTENT_REVISION_MISMATCH");
        }
        return record;
    }
    private static List<AlarmRecord> removePendingIntentDependentsLocked(ScopeState state, String tokenId) {
        List<AlarmRecord> alarms = new ArrayList<>();
        for (AlarmRecord value : state.alarms.values()) {
            if (tokenId.equals(value.pendingIntentTokenId)) alarms.add(value);
        }
        for (AlarmRecord value : alarms) state.alarms.remove(value.id);
        for (NotificationRecord value : state.notifications.values()) {
            if (tokenId.equals(value.contentIntentTokenId)) value.contentIntentTokenId = "";
            if (tokenId.equals(value.deleteIntentTokenId)) value.deleteIntentTokenId = "";
            if (value.actionIntentTokenIds.contains(tokenId)) {
                List<String> updated = new ArrayList<>(value.actionIntentTokenIds);
                updated.removeIf(tokenId::equals);
                value.actionIntentTokenIds = Collections.unmodifiableList(updated);
            }
        }
        return alarms;
    }

    private static boolean rebindPendingIntent(PendingIntentRecord record, String processName, long generation) {
        String owner = required(processName, "processName");
        if (record.ownerProcessName.equals(owner) && record.ownerGeneration == generation) return false;
        record.ownerProcessName = owner; record.ownerGeneration = generation;
        record.updatedAtMs = System.currentTimeMillis(); return true;
    }
    static String pendingIntentKind(String value) {
        String normalized = required(value, "pendingIntentKind").toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case VirtualPendingIntentSnapshot.BROADCAST, VirtualPendingIntentSnapshot.ACTIVITY,
                    VirtualPendingIntentSnapshot.ACTIVITY_RESULT, VirtualPendingIntentSnapshot.SERVICE,
                    VirtualPendingIntentSnapshot.FOREGROUND_SERVICE -> normalized;
            default -> throw new IllegalArgumentException("invalid PendingIntent kind");
        };
    }
    private static VirtualPendingIntentSnapshot pendingIntentSnapshot(PendingIntentRecord value) {
        return new VirtualPendingIntentSnapshot(value.tokenId, value.kind, value.requestCode,
                value.action, value.component, value.data, value.filterIdentity, value.flags, value.creatorPackage,
                value.creatorUid, value.requiredPermission, value.ownerProcessName,
                value.ownerGeneration, value.packageRevision, value.payload, value.sends,
                value.cancelled, value.updatedAtMs);
    }

    private static NotificationKey notificationKey(int guestId, String guestTag) {
        if (guestId < 0) throw new IllegalArgumentException("guestId must be non-negative");
        return new NotificationKey(guestId, normalizeTag(guestTag));
    }
    static String normalizeTag(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAX_KEY_CHARS) throw new IllegalArgumentException("notification tag too long");
        return normalized;
    }
    private static String hostNotificationTag(Scope scope, String guestTag, long generation) {
        return "cs:u" + scope.virtualUserId() + ":g" + generation + ":" + guestTag;
    }
    static String channelKey(String kind, String id) { return channelKind(kind) + "#" + required(id, "channelId"); }
    static String channelKind(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!VirtualNotificationChannelSnapshot.CHANNEL.equals(normalized)
                && !VirtualNotificationChannelSnapshot.GROUP.equals(normalized)) {
            throw new IllegalArgumentException("invalid notification channel kind");
        }
        return normalized;
    }
    static String notificationState(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!VirtualNotificationSnapshot.RESERVED.equals(normalized)
                && !VirtualNotificationSnapshot.ACTIVE.equals(normalized)) {
            throw new IllegalArgumentException("invalid notification state");
        }
        return normalized;
    }
    static String alarmDeliveryPath(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!VirtualAlarmSnapshot.LISTENER.equals(normalized)
                && !VirtualAlarmSnapshot.PENDING_INTENT.equals(normalized)) {
            throw new IllegalArgumentException("invalid alarm delivery path");
        }
        return normalized;
    }
    private static VirtualAlarmSnapshot alarmSnapshot(AlarmRecord value) {
        return new VirtualAlarmSnapshot(value.id, value.triggerAtMs, value.intervalMs,
                value.exact, value.allowWhileIdle, value.deliveryPath, value.pendingIntentTokenId,
                value.ownerProcessName, value.ownerGeneration, value.packageRevision,
                value.tokenPayload, value.deliveryCount, value.updatedAtMs,
                value.alarmClock, value.alarmClockPayload);
    }
    private void validateNotificationReferences(Scope scope, String revision,
                                                VirtualNotificationSnapshot value) {
        if (!value.contentIntentTokenId().isEmpty()) {
            requirePendingIntent(scope, revision, value.contentIntentTokenId());
        }
        if (!value.deleteIntentTokenId().isEmpty()) {
            requirePendingIntent(scope, revision, value.deleteIntentTokenId());
        }
        for (String tokenId : value.actionIntentTokenIds()) requirePendingIntent(scope, revision, tokenId);
    }
    private boolean pruneJobRevisionLocked(ScopeState state, String revision) {
        List<JobRecord> stale = new ArrayList<>();
        for (JobRecord value : state.jobs.values()) {
            if (!revision.equals(value.packageRevision)) stale.add(value);
        }
        for (JobRecord value : stale) {
            JobExecution execution = activeJobExecutions.get(value.hostId);
            if (execution != null) execution.invalidateLocked();
            state.jobs.remove(value.guestId);
        }
        return !stale.isEmpty();
    }
    private static long retryDelay(JobRecord job) {
        long multiplier;
        if (job.backoffPolicy == VirtualJobSnapshot.BACKOFF_LINEAR) {
            multiplier = Math.max(1, job.failureCount);
        } else {
            multiplier = 1L << Math.min(20, Math.max(0, job.failureCount - 1));
        }
        long value = job.initialBackoffMs > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : job.initialBackoffMs * multiplier;
        return Math.min(value, 5L * 60L * 60L * 1000L);
    }
    private static long safeAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
    private static boolean pruneNotificationRevisionLocked(ScopeState state, String revision) {
        int beforeNotifications = state.notifications.size();
        int beforeChannels = state.notificationChannels.size();
        state.notifications.values().removeIf(value -> !revision.equals(value.packageRevision));
        state.notificationChannels.values().removeIf(value -> !revision.equals(value.packageRevision));
        return beforeNotifications != state.notifications.size() || beforeChannels != state.notificationChannels.size();
    }
    static List<String> boundedTokenIds(List<String> values, String name) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > 32) throw new IllegalArgumentException(name + " exceeds 32 entries");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) continue;
            out.add(required(normalized, name));
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }


    static String jobState(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!VirtualJobSnapshot.RESERVED.equals(normalized) && !VirtualJobSnapshot.SCHEDULED.equals(normalized)
                && !VirtualJobSnapshot.DISPATCHING.equals(normalized)
                && !VirtualJobSnapshot.RUNNING.equals(normalized)) throw new IllegalArgumentException("invalid job state");
        return normalized;
    }
    private static VirtualNotificationSnapshot notificationSnapshot(NotificationRecord value) {
        return new VirtualNotificationSnapshot(value.guestId, value.hostId, value.guestTag, value.hostTag,
                value.channelId, value.state, value.packageRevision, value.contentIntentTokenId,
                value.deleteIntentTokenId, value.actionIntentTokenIds, value.foregroundService,
                value.foregroundServiceKey, value.payload, value.updatedAtMs);
    }
    private static VirtualJobSnapshot jobSnapshot(JobRecord value) {
        return new VirtualJobSnapshot(value.guestId, value.hostId, value.state, value.ownerProcessName,
                value.ownerGeneration, value.packageRevision, value.requiredNetworkType,
                value.requiresCharging, value.requiresBatteryNotLow, value.requiresStorageNotLow,
                value.requiresDeviceIdle, value.periodic, value.intervalMs, value.flexMs,
                value.minimumLatencyMs, value.overrideDeadlineMs, value.expedited, value.persisted,
                value.backoffPolicy, value.initialBackoffMs, value.failureCount, value.nextRunAtMs,
                value.lastFailureAtMs, value.payload, value.updatedAtMs);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (ScopeState state : states.values()) for (AlarmRecord alarm : state.alarms.values()) if (alarm.future != null) alarm.future.cancel(false);
        for (JobExecution execution : new ArrayList<>(activeJobExecutions.values())) execution.invalidateLocked();
        clientRegistry.clear(); scheduler.shutdownNow();
    }
    static byte[] boundedPayload(byte[] value, String name) {
        byte[] copy = value == null ? new byte[0] : value.clone();
        if (copy.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return copy;
    }


    static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_KEY_CHARS + " characters");
        }
        return normalized;
    }
    static String optionalIdentity(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_KEY_CHARS + " characters");
        }
        return normalized;
    }
    static String normalizeRequired(String value, String name) { return normalize(required(value, name)); }
    static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT); }
    static String safe(String value) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > MAX_SECRET_CHARS) {
            throw new IllegalArgumentException("virtual secret exceeds " + MAX_SECRET_CHARS + " characters");
        }
        return normalized;
    }
}
