package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

        VirtualNotificationSnapshot reservedNotification = store.reserveNotification(
                owner, 1L, 41, "updates", "general");
        store.commitNotification(owner, 41, "updates", "general", new byte[]{4, 1});
        store.upsertNotificationChannel(owner, VirtualNotificationChannelSnapshot.CHANNEL,
                "general", "", new byte[]{7});
        VirtualJobSnapshot reservedJob = store.reserveJob(owner, "guest.pkg", 1L,
                51, new byte[]{5, 1});
        store.commitJob(owner, 51);

        VirtualSystemServiceStore reloaded = new VirtualSystemServiceStore(root);
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
        require(reloaded.dispatchJob(reservedJob.hostId()), "owned scheduled job must dispatch");
        require(jobEvents.get() == 1 && deliveredJobId.get() == 51,
                "job callback must use Guest job ID and owning Generation");
        require(VirtualJobSnapshot.RUNNING.equals(reloaded.jobs(owner, "guest.pkg", 1L).get(0).state()),
                "dispatched job must transition to RUNNING");
        reloaded.finishJob(owner, 51, false);
        require(reloaded.jobs(owner, "guest.pkg", 1L).isEmpty(),
                "finished job must be removed when reschedule is false");
        VirtualJobSnapshot deferredJob = reloaded.reserveJob(owner, "guest.pkg:remote", 2L,
                52, new byte[]{5, 2});
        reloaded.commitJob(owner, 52);
        reloaded.register(second);
        require(!reloaded.dispatchJob(deferredJob.hostId()),
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
        volatile boolean closed;
        TestClient(VirtualSystemServiceStore.Scope scope, String processName, long generation,
                   AtomicInteger clipboardEvents, AtomicInteger alarmEvents,
                   AtomicInteger jobEvents, AtomicInteger deliveredJobId, boolean acceptJobs) {
            this.scope = scope; this.processName = processName; this.generation = generation;
            observer = new IVirtualSystemServiceObserver.Stub() {
                @Override public void onClipboardChanged() { clipboardEvents.incrementAndGet(); }
                @Override public void onAlarm(String alarmId) { alarmEvents.incrementAndGet(); }
                @Override public boolean onJobReady(int guestJobId, byte[] payload) {
                    deliveredJobId.set(guestJobId); jobEvents.incrementAndGet(); return acceptJobs;
                }
            };
        }
        @Override public VirtualSystemServiceStore.Scope scope() { return scope; }
        @Override public String processName() { return processName; }
        @Override public long generation() { return generation; }
        @Override public IVirtualSystemServiceObserver observer() { return observer; }
        @Override public boolean active() { return !closed; }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
