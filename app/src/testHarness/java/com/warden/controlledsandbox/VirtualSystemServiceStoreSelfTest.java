package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IVirtualJobExecution;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobWorkItemSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class VirtualSystemServiceStoreSelfTest {
    public static void main(String[] args) throws Exception {
        accountSecretEncryptionLifecycle();
        persistenceEnvelopeAndCorruptionLifecycle();
        alarmRecoveryAndRepeatingLifecycle();
        notificationOwnershipLifecycle();
        jobPolicyPersistenceAndRetryLifecycle();
        java.io.File root = Files.createTempDirectory("virtual-system-services").toFile();
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        VirtualSystemServiceStore.Scope owner = new VirtualSystemServiceStore.Scope("guest.pkg", 3);
        VirtualSystemServiceStore.Scope other = new VirtualSystemServiceStore.Scope("guest.pkg", 4);
        AtomicInteger firstClipboardEvents = new AtomicInteger();
        AtomicInteger secondClipboardEvents = new AtomicInteger();
        AtomicInteger replacementClipboardEvents = new AtomicInteger();
        AtomicInteger firstAlarmEvents = new AtomicInteger();
        AtomicInteger secondAlarmEvents = new AtomicInteger();
        AtomicInteger replacementAlarmEvents = new AtomicInteger();
        AtomicInteger firstDeviceProfileEvents = new AtomicInteger();
        AtomicInteger secondDeviceProfileEvents = new AtomicInteger();
        AtomicInteger replacementDeviceProfileEvents = new AtomicInteger();
        AtomicInteger jobEvents = new AtomicInteger();
        AtomicInteger deliveredJobId = new AtomicInteger(-1);
        TestClient first = new TestClient(owner, "guest.pkg", 1L, firstClipboardEvents, firstAlarmEvents,
                firstDeviceProfileEvents, jobEvents, deliveredJobId, true);
        TestClient second = new TestClient(owner, "guest.pkg:remote", 2L, secondClipboardEvents, secondAlarmEvents,
                secondDeviceProfileEvents, new AtomicInteger(), new AtomicInteger(-1), false);
        TestClient isolated = new TestClient(other, "guest.pkg", 1L,
                new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(-1), false);
        store.register(first); store.register(second); store.register(isolated);

        store.setClipboard(owner, new byte[]{1, 2, 3});
        Thread.sleep(100L);
        require(java.util.Arrays.equals(new byte[]{1, 2, 3}, store.clipboard(owner)), "clipboard must be shared");
        require(store.clipboard(other).length == 0, "clipboard must be isolated by virtual user");
        require(firstClipboardEvents.get() == 1 && secondClipboardEvents.get() == 1,
                "all unique sessions in one scope must receive clipboard changes");
        TestClient replacementFirst = new TestClient(owner, "guest.pkg", 1L,
                replacementClipboardEvents, replacementAlarmEvents, replacementDeviceProfileEvents,
                new AtomicInteger(), new AtomicInteger(-1), false);
        store.register(replacementFirst);
        store.setClipboard(owner, new byte[]{3, 2, 1});
        Thread.sleep(100L);
        require(firstClipboardEvents.get() == 1 && replacementClipboardEvents.get() == 1
                        && secondClipboardEvents.get() == 2,
                "reconnecting the same Generation must replace the stale observer");
        store.notifyDeviceProfileChanged(owner, 7L);
        Thread.sleep(100L);
        require(firstDeviceProfileEvents.get() == 0
                        && replacementDeviceProfileEvents.get() == 7
                        && secondDeviceProfileEvents.get() == 7,
                "device profile invalidation reaches only active observers in the matching scope");
        boolean oversizedClipboardRejected = false;
        try { store.setClipboard(owner, new byte[512 * 1024 + 1]); }
        catch (IllegalArgumentException expected) { oversizedClipboardRejected = true; }
        require(oversizedClipboardRejected, "oversized clipboard payload must fail closed");

        require(store.addAccount(owner, "alice", "mail", "pw"), "account must be added");
        store.setToken(owner, "alice", "mail", "access", "token");
        List<VirtualAccountSnapshot> accounts = store.accounts(owner, "mail");
        require(accounts.size() == 1 && accounts.get(0).tokens().contains("token"), "account state must be shared");
        require(store.setAccountVisibility(owner, "alice", "mail", 3)
                        && store.accountVisibility(owner, "alice", "mail") == 3,
                "account visibility must be owned by the virtual scope");
        require(store.accounts(other, "").isEmpty(), "accounts must be isolated by virtual user");

        int host = store.ensureNamespace(owner, "notification", 7);
        require(host == store.ensureNamespace(owner, "notification", 7), "namespace mapping must be stable");
        require(store.guestIdForHost(owner, "notification", host) == 7, "reverse namespace mapping required");

        store.scheduleAlarm(owner, "guest.pkg", 1L, "alarm-1",
                System.currentTimeMillis() + 40L, 0L, new byte[]{9});
        require(store.alarms(owner, "guest.pkg", 1L).size() == 1,
                "owner process must see its alarms");
        require(store.alarms(owner, "guest.pkg:remote", 2L).isEmpty(),
                "other guest processes must not steal alarms");
        Thread.sleep(250L);
        require(firstAlarmEvents.get() == 0 && replacementAlarmEvents.get() == 1,
                "alarm must dispatch once to the newest observer for its owning generation");
        require(secondAlarmEvents.get() == 0, "other guest processes must not receive owner alarms");
        require(store.alarms(owner, "guest.pkg", 1L).isEmpty(),
                "one-shot alarm must be removed after delivery");

        VirtualPendingIntentSnapshot pendingCandidate = new VirtualPendingIntentSnapshot(
                "", VirtualPendingIntentSnapshot.ACTIVITY_RESULT, 31, "guest.RESULT",
                "activity-token", "content://guest/result", "a=guest.RESULT|c=activity-token|d=content://guest/result", 0x02000000, "guest.pkg", 12003,
                "guest.permission.RESULT", "guest.pkg", 1L, "revision-a",
                new byte[]{3, 1}, 0, false, 1L);
        VirtualPendingIntentSnapshot reservedPending = store.reservePendingIntent(owner,
                "guest.pkg", 1L, "revision-a", 12003, pendingCandidate,
                false, false, false);
        require(!reservedPending.tokenId().isEmpty() && reservedPending.creatorUid() == 12003,
                "PendingIntent must receive durable token and virtual creator UID");
        boolean creatorUidRejected = false;
        try {
            store.reservePendingIntent(owner, "guest.pkg", 1L, "revision-a", 12003,
                    new VirtualPendingIntentSnapshot("", VirtualPendingIntentSnapshot.BROADCAST,
                            32, "guest.BAD_UID", "", "", "a=guest.BAD_UID|c=|d=", 0, "guest.pkg", 99999, "",
                            "guest.pkg", 1L, "revision-a", new byte[0], 0, false, 1L),
                    false, false, false);
        } catch (SecurityException expected) { creatorUidRejected = true; }
        require(creatorUidRejected, "Package Service must reject forged virtual creator UID");

        VirtualNotificationSnapshot reservedNotification = store.reserveNotification(
                owner, 1L, 41, "updates", "general");
        store.commitNotification(owner, 41, "updates", "general", new byte[]{4, 1});
        store.upsertNotificationChannel(owner, VirtualNotificationChannelSnapshot.CHANNEL,
                "general", "", new byte[]{7});
        VirtualJobSnapshot reservedJob = store.reserveJob(owner, "guest.pkg", 1L,
                51, new byte[]{5, 1});
        store.commitJob(owner, 51);

        VirtualSystemServiceStore reloaded = new VirtualSystemServiceStore(root);
        List<VirtualPendingIntentSnapshot> recoveredPending = reloaded.pendingIntents(
                owner, "guest.pkg", 2L, "revision-a");
        require(recoveredPending.size() == 1
                        && reservedPending.tokenId().equals(recoveredPending.get(0).tokenId())
                        && recoveredPending.get(0).ownerGeneration() == 2L,
                "durable PendingIntent must survive Package Service recreation and rebind generation");
        VirtualPendingIntentSnapshot sentPending = reloaded.markPendingIntentSent(
                owner, "revision-a", reservedPending.tokenId());
        require(sentPending.sends() == 1 && !sentPending.cancelled(),
                "persistent PendingIntent send count must commit");
        VirtualPendingIntentSnapshot revisionReplacement = reloaded.reservePendingIntent(owner,
                "guest.pkg", 3L, "revision-b", 12003,
                new VirtualPendingIntentSnapshot("", VirtualPendingIntentSnapshot.ACTIVITY_RESULT,
                        31, "guest.RESULT", "activity-token", "content://guest/result", "a=guest.RESULT|c=activity-token|d=content://guest/result", 0x02000000,
                        "guest.pkg", 12003, "guest.permission.RESULT", "guest.pkg", 3L,
                        "revision-b", new byte[]{3, 2}, 0, false, 2L),
                false, false, false);
        require(!revisionReplacement.tokenId().equals(reservedPending.tokenId())
                        && reloaded.pendingIntents(owner, "guest.pkg", 3L, "revision-b").size() == 1,
                "APK revision update must remove stale PendingIntent records");

        require(reloaded.notifications(owner).size() == 1
                        && VirtualNotificationSnapshot.ACTIVE.equals(reloaded.notifications(owner).get(0).state())
                        && reloaded.notifications(owner).get(0).hostId() == reservedNotification.hostId(),
                "active notification lifecycle must survive Package Service recreation");
        require(reloaded.accountVisibility(owner, "alice", "mail") == 3,
                "account visibility must survive Package Service recreation");
        require(reloaded.notificationChannels(owner).size() == 1
                        && "general".equals(reloaded.notificationChannels(owner).get(0).id()),
                "notification channel lifecycle must survive Package Service recreation");
        require(reloaded.jobs(owner, "guest.pkg", 1L).size() == 1
                        && VirtualJobSnapshot.SCHEDULED.equals(reloaded.jobs(owner, "guest.pkg", 1L).get(0).state())
                        && reloaded.jobs(owner, "guest.pkg", 1L).get(0).hostId() == reservedJob.hostId(),
                "scheduled job lifecycle must survive Package Service recreation");
        reloaded.register(first);
        AtomicBoolean hostFinished = new AtomicBoolean();
        AtomicBoolean hostReschedule = new AtomicBoolean(true);
        IHostJobCallback hostCallback = new IHostJobCallback.Stub() {
            @Override public void finishHostJob(int hostJobId, boolean needsReschedule) {
                require(hostJobId == reservedJob.hostId(), "host callback must retain host job identity");
                hostFinished.set(true); hostReschedule.set(needsReschedule);
            }
            @Override public VirtualJobWorkItemSnapshot dequeueHostWork(int hostJobId) { return null; }
            @Override public boolean completeHostWork(int hostJobId, int workId) { return false; }
        };
        require(reloaded.startJob(parameters(reservedJob.hostId()), hostCallback, 0),
                "owned scheduled job must dispatch");
        require(jobEvents.get() == 1 && deliveredJobId.get() == 51,
                "job callback must use Guest job ID and owning Generation");
        require(VirtualJobSnapshot.RUNNING.equals(reloaded.jobs(owner, "guest.pkg", 1L).get(0).state()),
                "accepted job must transition to RUNNING");
        first.execution.get().finish(false);
        require(hostFinished.get() && !hostReschedule.get(),
                "Guest jobFinished must complete the trusted host callback");
        require(reloaded.jobs(owner, "guest.pkg", 1L).isEmpty(),
                "finished job must be removed when reschedule is false");
        VirtualJobSnapshot stoppableJob = reloaded.reserveJob(owner, "guest.pkg", 1L,
                53, new byte[]{5, 3});
        reloaded.commitJob(owner, 53);
        first.execution.set(null);
        require(reloaded.startJob(parameters(stoppableJob.hostId()), new IHostJobCallback.Stub() {
                    @Override public void finishHostJob(int hostJobId, boolean needsReschedule) { }
                    @Override public VirtualJobWorkItemSnapshot dequeueHostWork(int hostJobId) { return null; }
                    @Override public boolean completeHostWork(int hostJobId, int workId) { return false; }
                }, 0), "second owned job must start");
        IVirtualJobExecution staleExecution = first.execution.get();
        require(reloaded.stopJob(stoppableJob.hostId(), 3, 7, "constraint"),
                "Guest onStopJob reschedule decision must reach Host JobService");
        require(first.stopEvents.get() == 1, "Guest onStopJob must run exactly once");
        staleExecution.finish(false);
        require(VirtualJobSnapshot.SCHEDULED.equals(reloaded.jobs(owner, "guest.pkg", 1L).stream()
                        .filter(value -> value.guestId() == 53).findFirst().orElseThrow().state()),
                "stale execution capability must not finish a rescheduled job");
        reloaded.removeJob(owner, 53);
        VirtualJobSnapshot recoveryJob = reloaded.reserveJob(owner, "guest.pkg", 1L,
                54, new byte[]{5, 4});
        reloaded.commitJob(owner, 54);
        first.execution.set(null);
        require(reloaded.startJob(parameters(recoveryJob.hostId()), new IHostJobCallback.Stub() {
                    @Override public void finishHostJob(int hostJobId, boolean needsReschedule) { }
                    @Override public VirtualJobWorkItemSnapshot dequeueHostWork(int hostJobId) { return null; }
                    @Override public boolean completeHostWork(int hostJobId, int workId) { return false; }
                }, 0), "recovery job must enter RUNNING");
        VirtualSystemServiceStore recoveredWhileRunning = new VirtualSystemServiceStore(root);
        require(VirtualJobSnapshot.SCHEDULED.equals(recoveredWhileRunning
                        .jobs(owner, "guest.pkg", 1L).stream()
                        .filter(value -> value.guestId() == 54).findFirst().orElseThrow().state()),
                "Package Service recreation must recover stale RUNNING jobs to SCHEDULED");
        recoveredWhileRunning.close();
        reloaded.stopJob(recoveryJob.hostId(), 0, -1, "cleanup");
        reloaded.removeJob(owner, 54);
        VirtualJobSnapshot deferredJob = reloaded.reserveJob(owner, "guest.pkg:remote", 2L,
                52, new byte[]{5, 2});
        reloaded.commitJob(owner, 52);
        reloaded.register(second);
        require(!reloaded.startJob(parameters(deferredJob.hostId()), new IHostJobCallback.Stub() {
                    @Override public void finishHostJob(int hostJobId, boolean needsReschedule) { }
                    @Override public VirtualJobWorkItemSnapshot dequeueHostWork(int hostJobId) { return null; }
                    @Override public boolean completeHostWork(int hostJobId, int workId) { return false; }
                }, 0),
                "job callback without Guest execution acknowledgement must request host reschedule");
        require(VirtualJobSnapshot.SCHEDULED.equals(
                        reloaded.jobs(owner, "guest.pkg:remote", 2L).stream()
                                .filter(value -> value.guestId() == 52).findFirst().orElseThrow().state()),
                "unacknowledged job must remain SCHEDULED");
        reloaded.unregister(second);
        reloaded.unregister(first);
        reloaded.close();

        store.deleteScopeBestEffort(owner);
        require(store.clipboard(owner).length == 0, "instance deletion must clear shared system-service state");
        require(store.accounts(owner, "").isEmpty(), "instance deletion must clear accounts");

        first.closed = true; store.unregister(first);
        store.close();
        System.out.println("PASS Binder-owned virtual system-service store self-test");
    }

    private static void alarmRecoveryAndRepeatingLifecycle() throws Exception {
        java.io.File root = Files.createTempDirectory("virtual-alarm-recovery").toFile();
        VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope("alarm.pkg", 8);
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        VirtualPendingIntentSnapshot pending = store.reservePendingIntent(scope, "alarm.pkg", 1L,
                "alarm-rev-a", 18008, new VirtualPendingIntentSnapshot("",
                        VirtualPendingIntentSnapshot.BROADCAST, 71, "alarm.ACTION", "", "",
                        "a=alarm.ACTION|c=|d=", 0x04000000, "alarm.pkg", 18008, "",
                        "alarm.pkg", 1L, "alarm-rev-a", new byte[]{7, 1}, 0, false, 1L),
                false, false, false);
        long trigger = System.currentTimeMillis() + 450L;
        store.scheduleAlarm(scope, "alarm.pkg", 1L, "alarm-rev-a",
                new VirtualAlarmSnapshot("alarm-pi", trigger, 0L, true, true,
                        VirtualAlarmSnapshot.PENDING_INTENT, pending.tokenId(), "alarm.pkg", 1L,
                        "alarm-rev-a", new byte[0], 0, System.currentTimeMillis(), true,
                        new byte[]{9, 9, 1}));
        VirtualAlarmSnapshot scheduled = store.alarms(scope, "alarm.pkg", 1L, "alarm-rev-a").get(0);
        require(scheduled.exact() && scheduled.allowWhileIdle()
                        && VirtualAlarmSnapshot.PENDING_INTENT.equals(scheduled.deliveryPath())
                        && pending.tokenId().equals(scheduled.pendingIntentTokenId())
                        && scheduled.alarmClock() && java.util.Arrays.equals(
                        scheduled.alarmClockPayload(), new byte[]{9, 9, 1}),
                "exact PendingIntent alarm metadata must be durable");
        store.close();

        VirtualSystemServiceStore recovered = new VirtualSystemServiceStore(root);
        AtomicInteger alarmEvents = new AtomicInteger();
        TestClient client = new TestClient(scope, "alarm.pkg", 2L, new AtomicInteger(), alarmEvents,
                new AtomicInteger(), new AtomicInteger(-1), false);
        recovered.register(client);
        List<VirtualAlarmSnapshot> rebound = recovered.alarms(scope, "alarm.pkg", 2L, "alarm-rev-a");
        require(rebound.size() == 1 && rebound.get(0).ownerGeneration() == 2L,
                "Package Service recovery must rebind an alarm to the current Guest generation");
        Thread.sleep(700L);
        require(alarmEvents.get() == 1
                        && recovered.alarms(scope, "alarm.pkg", 2L, "alarm-rev-a").isEmpty(),
                "recovered one-shot alarm must deliver once and then be removed");

        recovered.scheduleAlarm(scope, "alarm.pkg", 2L, "alarm-rev-a",
                new VirtualAlarmSnapshot("alarm-repeat", System.currentTimeMillis() + 30L, 90L,
                        false, false, VirtualAlarmSnapshot.LISTENER, "", "alarm.pkg", 2L,
                        "alarm-rev-a", new byte[]{7, 2}, 0, System.currentTimeMillis()));
        Thread.sleep(320L);
        List<VirtualAlarmSnapshot> repeating = recovered.alarms(scope, "alarm.pkg", 2L, "alarm-rev-a");
        require(alarmEvents.get() >= 3 && repeating.size() == 1
                        && repeating.get(0).deliveryCount() >= 2
                        && repeating.get(0).intervalMs() == 90L,
                "repeating listener alarm must persist delivery count and next trigger");
        require(recovered.cancelAlarm(scope, "alarm-rev-a", "alarm-repeat"),
                "repeating alarm cancellation must remove the owned schedule");

        recovered.scheduleAlarm(scope, "alarm.pkg", 2L, "alarm-rev-a",
                new VirtualAlarmSnapshot("alarm-stale", System.currentTimeMillis() + 60_000L, 0L,
                        false, false, VirtualAlarmSnapshot.LISTENER, "", "alarm.pkg", 2L,
                        "alarm-rev-a", new byte[0], 0, System.currentTimeMillis()));
        require(recovered.alarms(scope, "alarm.pkg", 3L, "alarm-rev-b").isEmpty(),
                "APK revision query must prune stale alarms instead of leaking them");
        recovered.unregister(client);
        recovered.close();
    }

    private static void notificationOwnershipLifecycle() throws Exception {
        java.io.File root = Files.createTempDirectory("virtual-notification-lifecycle").toFile();
        VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope("notify.pkg", 9);
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        VirtualPendingIntentSnapshot content = reserveNotificationPendingIntent(store, scope, 81, "notify.CONTENT");
        VirtualPendingIntentSnapshot deletion = reserveNotificationPendingIntent(store, scope, 82, "notify.DELETE");
        VirtualPendingIntentSnapshot action = reserveNotificationPendingIntent(store, scope, 83, "notify.ACTION");

        store.upsertNotificationChannel(scope, "notify-rev-a",
                new VirtualNotificationChannelSnapshot(VirtualNotificationChannelSnapshot.GROUP,
                        "messages", "", "notify-rev-a", new byte[]{8, 1}, System.currentTimeMillis()));
        store.upsertNotificationChannel(scope, "notify-rev-a",
                new VirtualNotificationChannelSnapshot(VirtualNotificationChannelSnapshot.CHANNEL,
                        "direct", "messages", "notify-rev-a", new byte[]{8, 2}, System.currentTimeMillis()));
        VirtualNotificationSnapshot candidate = new VirtualNotificationSnapshot(91, 0, "foreground", "",
                "direct", VirtualNotificationSnapshot.RESERVED, "notify-rev-a", content.tokenId(),
                deletion.tokenId(), List.of(action.tokenId()), true, "notify.pkg/.SyncService",
                new byte[0], System.currentTimeMillis());
        VirtualNotificationSnapshot reserved = store.reserveNotification(scope, 1L, "notify-rev-a", candidate);
        store.commitNotification(scope, "notify-rev-a", new VirtualNotificationSnapshot(91,
                reserved.hostId(), "foreground", reserved.hostTag(), "direct",
                VirtualNotificationSnapshot.ACTIVE, "notify-rev-a", content.tokenId(), deletion.tokenId(),
                List.of(action.tokenId()), true, "notify.pkg/.SyncService", new byte[]{9, 1},
                System.currentTimeMillis()));
        store.close();

        VirtualSystemServiceStore recovered = new VirtualSystemServiceStore(root);
        List<VirtualNotificationSnapshot> notifications = recovered.notifications(scope, "notify-rev-a");
        require(notifications.size() == 1 && notifications.get(0).hostId() == reserved.hostId()
                        && notifications.get(0).foregroundService()
                        && "notify.pkg/.SyncService".equals(notifications.get(0).foregroundServiceKey())
                        && content.tokenId().equals(notifications.get(0).contentIntentTokenId())
                        && deletion.tokenId().equals(notifications.get(0).deleteIntentTokenId())
                        && notifications.get(0).actionIntentTokenIds().equals(List.of(action.tokenId())),
                "notification recovery must retain FGS mapping and click/delete/action PendingIntent identity");
        require(recovered.notificationChannels(scope, "notify-rev-a").size() == 2,
                "notification group and channel lifecycle must survive Package Service recreation");

        require(recovered.cancelPendingIntent(scope, "notify-rev-a", deletion.tokenId()),
                "owned delete PendingIntent cancellation must succeed");
        VirtualNotificationSnapshot cleared = recovered.notifications(scope, "notify-rev-a").get(0);
        require(cleared.deleteIntentTokenId().isEmpty()
                        && content.tokenId().equals(cleared.contentIntentTokenId()),
                "PendingIntent cancellation must clear only the matching notification reference");

        require(recovered.removeNotificationChannel(scope, "notify-rev-a",
                        VirtualNotificationChannelSnapshot.GROUP, "messages"),
                "notification group deletion must remove the owned group");
        require(recovered.notificationChannels(scope, "notify-rev-a").isEmpty()
                        && recovered.notifications(scope, "notify-rev-a").isEmpty(),
                "group deletion must cascade through channels and notifications");

        store = recovered;
        reserveNotificationPendingIntent(store, scope, 84, "notify.REVISION");
        store.upsertNotificationChannel(scope, "notify-rev-a",
                new VirtualNotificationChannelSnapshot(VirtualNotificationChannelSnapshot.CHANNEL,
                        "stale", "", "notify-rev-a", new byte[0], System.currentTimeMillis()));
        require(store.notificationChannels(scope, "notify-rev-b").isEmpty()
                        && store.notifications(scope, "notify-rev-b").isEmpty(),
                "APK revision update must prune stale notification state");
        store.close();
    }

    private static void jobPolicyPersistenceAndRetryLifecycle() throws Exception {
        java.io.File root = Files.createTempDirectory("virtual-job-policy").toFile();
        VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope("job.pkg", 10);
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        long now = System.currentTimeMillis();
        VirtualJobSnapshot candidate = new VirtualJobSnapshot(81, 0, VirtualJobSnapshot.RESERVED,
                "job.pkg:worker", 1L, "job-rev-a", VirtualJobSnapshot.NETWORK_UNMETERED,
                true, true, true, true, false, 0L, 0L, 0L, 60_000L,
                true, true, VirtualJobSnapshot.BACKOFF_LINEAR, 50L,
                0, now, 0L, new byte[]{8, 1}, now);
        VirtualJobSnapshot reserved = store.reserveJob(scope, "job.pkg:worker", 1L,
                "job-rev-a", candidate);
        store.commitJob(scope, 81);
        store.close();

        VirtualSystemServiceStore reloaded = new VirtualSystemServiceStore(root);
        VirtualJobSnapshot restored = reloaded.jobs(scope, "job.pkg:worker", 1L,
                "job-rev-a").get(0);
        require(restored.hostId() == reserved.hostId()
                        && restored.requiredNetworkType() == VirtualJobSnapshot.NETWORK_UNMETERED
                        && restored.requiresCharging() && restored.requiresBatteryNotLow()
                        && restored.requiresStorageNotLow() && restored.requiresDeviceIdle()
                        && restored.expedited() && restored.persisted()
                        && restored.backoffPolicy() == VirtualJobSnapshot.BACKOFF_LINEAR,
                "typed Job constraints must survive Package Service recreation");
        AtomicInteger starts = new AtomicInteger();
        TestClient client = new TestClient(scope, "job.pkg:worker", 1L,
                new AtomicInteger(), new AtomicInteger(), starts, new AtomicInteger(-1), true);
        reloaded.register(client);
        require(reloaded.startJob(parameters(restored.hostId()), new IHostJobCallback.Stub() {
                    @Override public void finishHostJob(int hostJobId, boolean needsReschedule) { }
                    @Override public VirtualJobWorkItemSnapshot dequeueHostWork(int hostJobId) { return null; }
                    @Override public boolean completeHostWork(int hostJobId, int workId) { return false; }
                }, 0), "eligible persisted job must dispatch");
        client.execution.get().finish(true);
        VirtualJobSnapshot retried = reloaded.jobs(scope, "job.pkg:worker", 1L,
                "job-rev-a").get(0);
        require(retried.failureCount() == 1 && retried.lastFailureAtMs() > 0L
                        && retried.nextRunAtMs() - retried.lastFailureAtMs() == 50L,
                "linear retry must persist failure count and bounded backoff deadline");

        long periodicNow = System.currentTimeMillis();
        VirtualJobSnapshot periodic = new VirtualJobSnapshot(82, 0, VirtualJobSnapshot.RESERVED,
                "job.pkg:worker", 1L, "job-rev-a", VirtualJobSnapshot.NETWORK_ANY,
                false, false, false, false, true, 1_000L, 200L, 0L, 0L,
                false, true, VirtualJobSnapshot.BACKOFF_EXPONENTIAL, 40L,
                0, periodicNow, 0L, new byte[]{8, 2}, periodicNow);
        VirtualJobSnapshot periodicReserved = reloaded.reserveJob(scope, "job.pkg:worker", 1L,
                "job-rev-a", periodic);
        reloaded.commitJob(scope, 82);
        require(reloaded.startJob(parameters(periodicReserved.hostId()), new IHostJobCallback.Stub() {
                    @Override public void finishHostJob(int hostJobId, boolean needsReschedule) { }
                    @Override public VirtualJobWorkItemSnapshot dequeueHostWork(int hostJobId) { return null; }
                    @Override public boolean completeHostWork(int hostJobId, int workId) { return false; }
                }, 0), "periodic job must dispatch");
        client.execution.get().finish(false);
        VirtualJobSnapshot nextPeriod = reloaded.jobs(scope, "job.pkg:worker", 1L,
                "job-rev-a").stream().filter(value -> value.guestId() == 82).findFirst().orElseThrow();
        require(VirtualJobSnapshot.SCHEDULED.equals(nextPeriod.state()) && nextPeriod.failureCount() == 0
                        && nextPeriod.nextRunAtMs() >= nextPeriod.updatedAtMs() + 1_000L,
                "successful periodic job must remain scheduled for its next interval");
        require(reloaded.jobs(scope, "job.pkg:worker", 2L, "job-rev-b").isEmpty(),
                "APK revision update must prune stale persisted jobs");
        reloaded.unregister(client);
        reloaded.close();
    }

    private static void accountSecretEncryptionLifecycle() throws Exception {
        java.io.File root = Files.createTempDirectory("virtual-system-secret").toFile();
        java.io.File stateFile = new java.io.File(root, "sandbox-system-services.json");
        java.io.File keyFile = new java.io.File(root, "sandbox-system-services.secrets.key");
        VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope("secret.pkg", 9);
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        require(store.addAccount(scope, "alice", "mail", "plain-password"),
                "encrypted account must be added");
        store.setToken(scope, "alice", "mail", "access", "plain-token");
        store.close();

        require(keyFile.isFile() && keyFile.length() == VirtualSecretCipher.KEY_BYTES,
                "per-install secret key must be persisted with fixed size");
        JSONObject envelope = new JSONObject(Files.readString(stateFile.toPath(), StandardCharsets.UTF_8));
        JSONObject payload = new JSONObject(envelope.getString("payload"));
        JSONObject account = payload.getJSONArray("scopes").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        require(!account.keySet().contains("password") && !account.keySet().contains("tokens"),
                "schema 6 must not persist plaintext secret fields");
        require(account.getString("passwordEncrypted").startsWith(VirtualSecretCipher.PREFIX)
                        && account.getJSONObject("tokensEncrypted").getString("access")
                                .startsWith(VirtualSecretCipher.PREFIX),
                "passwords and tokens must use authenticated ciphertext");

        VirtualSystemServiceStore recovered = new VirtualSystemServiceStore(root);
        require("plain-password".equals(recovered.password(scope, "alice", "mail"))
                        && "plain-token".equals(recovered.token(scope, "alice", "mail", "access")),
                "encrypted secrets must round-trip with the same installation key");
        recovered.close();

        java.io.File foreignRoot = Files.createTempDirectory("virtual-system-secret-foreign").toFile();
        Files.copy(stateFile.toPath(), new java.io.File(foreignRoot, stateFile.getName()).toPath());
        VirtualSystemServiceStore foreign = new VirtualSystemServiceStore(foreignRoot);
        require(foreign.accounts(scope, "").isEmpty()
                        && foreign.maintenanceWarning().contains("DECRYPTION_FAILED"),
                "encrypted store without its installation key must fail closed");
        foreign.close();
    }

    private static void persistenceEnvelopeAndCorruptionLifecycle() throws Exception {
        java.io.File root = Files.createTempDirectory("virtual-system-store-envelope").toFile();
        java.io.File stateFile = new java.io.File(root, "sandbox-system-services.json");
        VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope("persist.pkg", 17);
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        store.setClipboard(scope, new byte[]{4, 2});
        store.close();

        String envelope = Files.readString(stateFile.toPath(), StandardCharsets.UTF_8);
        JSONObject parsedEnvelope = new JSONObject(envelope);
        require(parsedEnvelope.optInt("envelopeVersion", -1) == 1
                        && parsedEnvelope.keySet().contains("crc32")
                        && parsedEnvelope.keySet().contains("payload"),
                "new virtual system-service writes must use a checksummed envelope");
        VirtualSystemServiceStore recovered = new VirtualSystemServiceStore(root);
        require(java.util.Arrays.equals(new byte[]{4, 2}, recovered.clipboard(scope)),
                "checksummed store must restore valid state");
        recovered.close();

        JSONObject legacyAccount = new JSONObject().put("name", "legacy")
                .put("type", "mail").put("password", "legacy-password")
                .put("tokens", new JSONObject().put("access", "legacy-token"));
        JSONObject legacyScope = new JSONObject().put("packageName", scope.packageName())
                .put("virtualUserId", scope.virtualUserId())
                .put("clipboard", java.util.Base64.getEncoder().encodeToString(new byte[]{7, 1}))
                .put("accounts", new org.json.JSONArray().put(legacyAccount));
        String legacyPayload = new JSONObject().put("schemaVersion", 5)
                .put("nextNotificationHostId", 0x51000000)
                .put("nextJobHostId", 0x52000000)
                .put("nextPendingIntentToken", 1L)
                .put("scopes", new org.json.JSONArray().put(legacyScope)).toString();
        Files.writeString(stateFile.toPath(), legacyPayload, StandardCharsets.UTF_8);
        VirtualSystemServiceStore legacyRecovered = new VirtualSystemServiceStore(root);
        require(java.util.Arrays.equals(new byte[]{7, 1}, legacyRecovered.clipboard(scope))
                        && "legacy-password".equals(legacyRecovered.password(scope, "legacy", "mail"))
                        && "legacy-token".equals(legacyRecovered.token(scope, "legacy", "mail", "access")),
                "legacy raw schema 1-5 payload must remain readable and migrate secrets");
        legacyRecovered.close();
        JSONObject migratedEnvelope = new JSONObject(Files.readString(stateFile.toPath(), StandardCharsets.UTF_8));
        JSONObject migratedPayload = new JSONObject(migratedEnvelope.getString("payload"));
        JSONObject migratedAccount = migratedPayload.getJSONArray("scopes").getJSONObject(0)
                .getJSONArray("accounts").getJSONObject(0);
        require(migratedPayload.optInt("schemaVersion", -1) == 6
                        && migratedAccount.keySet().contains("passwordEncrypted")
                        && !migratedAccount.keySet().contains("password"),
                "legacy account secrets must be rewritten to encrypted schema immediately");

        String validEnvelope = Files.readString(stateFile.toPath(), StandardCharsets.UTF_8);
        JSONObject corruptObject = new JSONObject(validEnvelope).put("crc32", "0");
        Files.writeString(stateFile.toPath(), corruptObject.toString(), StandardCharsets.UTF_8);
        VirtualSystemServiceStore corruptRecovered = new VirtualSystemServiceStore(root);
        require(corruptRecovered.clipboard(scope).length == 0,
                "checksum mismatch must fail closed to empty state");
        require(corruptRecovered.maintenanceWarning().contains("CHECKSUM_MISMATCH")
                        && new java.io.File(root, "sandbox-system-services.json.corrupt").isFile(),
                "checksum mismatch must be quarantined with a maintenance warning");
        corruptRecovered.close();

        byte[] oversized = new byte[VirtualSystemServiceStorePersistence.MAX_FILE_BYTES + 1];
        Files.write(stateFile.toPath(), oversized);
        VirtualSystemServiceStore oversizedRecovered = new VirtualSystemServiceStore(root);
        require(oversizedRecovered.maintenanceWarning().contains("FILE_LIMIT_EXCEEDED"),
                "oversized persistent state must fail closed before JSON parsing");
        oversizedRecovered.close();
    }

    private static VirtualPendingIntentSnapshot reserveNotificationPendingIntent(
            VirtualSystemServiceStore store, VirtualSystemServiceStore.Scope scope,
            int requestCode, String action) {
        return store.reservePendingIntent(scope, "notify.pkg", 1L, "notify-rev-a", 19009,
                new VirtualPendingIntentSnapshot("", VirtualPendingIntentSnapshot.BROADCAST,
                        requestCode, action, "", "", "a=" + action + "|c=|d=", 0x04000000,
                        "notify.pkg", 19009, "", "notify.pkg", 1L, "notify-rev-a",
                        new byte[]{(byte) requestCode}, 0, false, System.currentTimeMillis()),
                false, false, false);
    }

    private static final class TestClient implements VirtualSystemServiceStore.Client {
        final VirtualSystemServiceStore.Scope scope;
        final String processName;
        final long generation;
        final IVirtualSystemServiceObserver observer;
        final AtomicReference<IVirtualJobExecution> execution = new AtomicReference<>();
        final AtomicInteger stopEvents = new AtomicInteger();
        volatile boolean closed;
        TestClient(VirtualSystemServiceStore.Scope scope, String processName, long generation,
                   AtomicInteger clipboardEvents, AtomicInteger alarmEvents,
                   AtomicInteger jobEvents, AtomicInteger deliveredJobId, boolean acceptJobs) {
            this(scope, processName, generation, clipboardEvents, alarmEvents, new AtomicInteger(),
                    jobEvents, deliveredJobId, acceptJobs);
        }
        TestClient(VirtualSystemServiceStore.Scope scope, String processName, long generation,
                   AtomicInteger clipboardEvents, AtomicInteger alarmEvents,
                   AtomicInteger deviceProfileVersion, AtomicInteger jobEvents,
                   AtomicInteger deliveredJobId, boolean acceptJobs) {
            this.scope = scope; this.processName = processName; this.generation = generation;
            observer = new IVirtualSystemServiceObserver.Stub() {
                @Override public void onClipboardChanged() { clipboardEvents.incrementAndGet(); }
                @Override public void onDeviceServiceProfileChanged(long policyVersion) {
                    deviceProfileVersion.set(Math.toIntExact(policyVersion));
                }
                @Override public void onInteractionProfileChanged(long policyVersion) { }
                @Override public void onNetworkServiceProfileChanged(long policyVersion) { }
                @Override public void onApplicationEnvironmentProfileChanged(long policyVersion) { }
                @Override public void onCompatibilityProfileChanged(long policyVersion) { }
                @Override public void onPolicyServicesProfileChanged(long policyVersion) { }
                @Override public void onMediaCommunicationProfileChanged(long policyVersion) { }
                @Override public void onPeripheralServicesProfileChanged(long policyVersion) { }
                @Override public void onPrivilegedServicesProfileChanged(long policyVersion) { }
                @Override public void onApplicationEnvironmentDataChanged(String domain, String key) { }
                @Override public void onAlarm(com.warden.controlledsandbox.contract.VirtualAlarmSnapshot alarm) { alarmEvents.incrementAndGet(); }
                @Override public boolean onJobStart(int guestJobId, byte[] payload,
                                                    VirtualJobParametersSnapshot parameters,
                                                    IVirtualJobExecution jobExecution) {
                    deliveredJobId.set(guestJobId); jobEvents.incrementAndGet();
                    execution.set(jobExecution); return acceptJobs;
                }
                @Override public boolean onJobStop(int guestJobId,
                                                   VirtualJobParametersSnapshot parameters) {
                    stopEvents.incrementAndGet(); return true;
                }
            };
        }
        @Override public VirtualSystemServiceStore.Scope scope() { return scope; }
        @Override public String processName() { return processName; }
        @Override public long generation() { return generation; }
        @Override public IVirtualSystemServiceObserver observer() { return observer; }
        @Override public boolean active() { return !closed; }
    }
    private static VirtualJobParametersSnapshot parameters(int hostJobId) {
        return new VirtualJobParametersSnapshot(hostJobId, -1, "", new byte[0], new byte[0],
                new byte[0], 0, false, false, false, List.of(), List.of(), new byte[0],
                0, -1, "", 0L);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
