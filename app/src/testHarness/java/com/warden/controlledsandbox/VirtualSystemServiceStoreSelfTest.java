package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IVirtualJobExecution;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class VirtualSystemServiceStoreSelfTest {
    public static void main(String[] args) throws Exception {
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
        AtomicInteger jobEvents = new AtomicInteger();
        AtomicInteger deliveredJobId = new AtomicInteger(-1);
        TestClient first = new TestClient(owner, "guest.pkg", 1L, firstClipboardEvents, firstAlarmEvents,
                jobEvents, deliveredJobId, true);
        TestClient second = new TestClient(owner, "guest.pkg:remote", 2L, secondClipboardEvents, secondAlarmEvents,
                new AtomicInteger(), new AtomicInteger(-1), false);
        TestClient isolated = new TestClient(other, "guest.pkg", 1L,
                new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger(-1), false);
        store.register(first); store.register(second); store.register(isolated);

        store.setClipboard(owner, new byte[]{1, 2, 3});
        Thread.sleep(100L);
        require(java.util.Arrays.equals(new byte[]{1, 2, 3}, store.clipboard(owner)), "clipboard must be shared");
        require(store.clipboard(other).length == 0, "clipboard must be isolated by virtual user");
        require(firstClipboardEvents.get() == 1 && secondClipboardEvents.get() == 1,
                "all unique sessions in one scope must receive clipboard changes");
        TestClient replacementFirst = new TestClient(owner, "guest.pkg", 1L,
                replacementClipboardEvents, replacementAlarmEvents, new AtomicInteger(),
                new AtomicInteger(-1), false);
        store.register(replacementFirst);
        store.setClipboard(owner, new byte[]{3, 2, 1});
        Thread.sleep(100L);
        require(firstClipboardEvents.get() == 1 && replacementClipboardEvents.get() == 1
                        && secondClipboardEvents.get() == 2,
                "reconnecting the same Generation must replace the stale observer");
        boolean oversizedClipboardRejected = false;
        try { store.setClipboard(owner, new byte[512 * 1024 + 1]); }
        catch (IllegalArgumentException expected) { oversizedClipboardRejected = true; }
        require(oversizedClipboardRejected, "oversized clipboard payload must fail closed");

        require(store.addAccount(owner, "alice", "mail", "pw"), "account must be added");
        store.setToken(owner, "alice", "mail", "access", "token");
        List<VirtualAccountSnapshot> accounts = store.accounts(owner, "mail");
        require(accounts.size() == 1 && accounts.get(0).tokens().contains("token"), "account state must be shared");
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
            this.scope = scope; this.processName = processName; this.generation = generation;
            observer = new IVirtualSystemServiceObserver.Stub() {
                @Override public void onClipboardChanged() { clipboardEvents.incrementAndGet(); }
                @Override public void onAlarm(String alarmId) { alarmEvents.incrementAndGet(); }
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
