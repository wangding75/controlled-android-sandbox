package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class VirtualSystemServiceStoreSelfTest {
    public static void main(String[] args) throws Exception {
        java.io.File root = Files.createTempDirectory("virtual-system-services").toFile();
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        VirtualSystemServiceStore.Scope owner = new VirtualSystemServiceStore.Scope("guest.pkg", 3);
        VirtualSystemServiceStore.Scope other = new VirtualSystemServiceStore.Scope("guest.pkg", 4);
        AtomicInteger clipboardEvents = new AtomicInteger();
        AtomicInteger firstAlarmEvents = new AtomicInteger();
        AtomicInteger secondAlarmEvents = new AtomicInteger();
        TestClient first = new TestClient(owner, "guest.pkg", 1L, clipboardEvents, firstAlarmEvents);
        TestClient second = new TestClient(owner, "guest.pkg:remote", 2L, clipboardEvents, secondAlarmEvents);
        TestClient isolated = new TestClient(other, "guest.pkg", 1L,
                new AtomicInteger(), new AtomicInteger());
        store.register(first); store.register(second); store.register(isolated);

        store.setClipboard(owner, new byte[]{1, 2, 3});
        Thread.sleep(100L);
        require(java.util.Arrays.equals(new byte[]{1, 2, 3}, store.clipboard(owner)), "clipboard must be shared");
        require(store.clipboard(other).length == 0, "clipboard must be isolated by virtual user");
        require(clipboardEvents.get() == 2, "all sessions in one scope must receive clipboard changes");
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
        require(firstAlarmEvents.get() == 1, "alarm must dispatch once to its owning generation");
        require(secondAlarmEvents.get() == 0, "other guest processes must not receive owner alarms");
        require(store.alarms(owner, "guest.pkg", 1L).isEmpty(),
                "one-shot alarm must be removed after delivery");

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
                   AtomicInteger clipboardEvents, AtomicInteger alarmEvents) {
            this.scope = scope; this.processName = processName; this.generation = generation;
            observer = new IVirtualSystemServiceObserver.Stub() {
                @Override public void onClipboardChanged() { clipboardEvents.incrementAndGet(); }
                @Override public void onAlarm(String alarmId) { alarmEvents.incrementAndGet(); }
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
