package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Host-side evidence for bounded per-Guest system-service state and namespace rewriting. */
public final class VirtualSystemServiceSelfTest {
    public static void main(String[] args) throws Exception {
        testClipboardIsolation();
        testAccountIsolation();
        testAlarmLifecycle();
        testNotificationNamespace();
        testNotificationFailureRollback();
        testJobNamespace();
        testJobFailureRollbackAndCancelAllBoundary();
        System.out.println("PASS virtual Alarm/Clipboard/Account/Notification/Job self-test");
    }

    private static void testClipboardIsolation() {
        GuestIdentity first = identity("guest.one", 0, 3L);
        GuestIdentity second = identity("guest.two", 0, 3L);
        FakeClipboardDelegate delegate = new FakeClipboardDelegate();
        ClipboardApi one = proxy(ClipboardApi.class, delegate, first, "clipboard");
        ClipboardApi two = proxy(ClipboardApi.class, delegate, second, "clipboard");
        Listener listener = new Listener();
        one.addPrimaryClipChangedListener(listener, "guest.one");
        one.setPrimaryClip(new FakeClip("one"), "guest.one");
        require("one".equals(one.getPrimaryClip("guest.one").text), "first clipboard value");
        require(two.getPrimaryClip("guest.two") == null, "clipboard isolated by Guest identity");
        require(listener.events == 1, "virtual clipboard listener dispatched");
        require(delegate.calls == 0, "host clipboard never called");
    }

    private static void testAccountIsolation() {
        GuestIdentity identity = identity("guest.accounts", 2, 8L);
        FakeAccountDelegate delegate = new FakeAccountDelegate();
        AccountApi accounts = proxy(AccountApi.class, delegate, identity, "account");
        Account account = new Account("alice", "mail");
        require(accounts.addAccountExplicitly(account, "secret", null), "virtual account added");
        require(accounts.getAccountsAsUser(2).length == 1, "virtual account listed");
        accounts.setAuthToken(account, "access", "token-1");
        require("token-1".equals(accounts.peekAuthToken(account, "access")), "virtual token stored");
        accounts.setPassword(account, "next");
        require("next".equals(accounts.getPassword(account)), "virtual password stored");
        require(accounts.removeAccountExplicitly(account), "virtual account removed");
        require(accounts.getAccountsAsUser(2).length == 0 && delegate.calls == 0,
                "host accounts remain hidden");
    }

    private static void testAlarmLifecycle() throws Exception {
        GuestIdentity identity = identity("guest.alarm", 0, 4L);
        FakeAlarmDelegate delegate = new FakeAlarmDelegate();
        AlarmApi alarms = proxy(AlarmApi.class, delegate, identity, "alarm");
        AlarmTarget target = new AlarmTarget();
        alarms.set(0, System.currentTimeMillis() + 25L, target, "guest.alarm");
        require(target.latch.await(2, TimeUnit.SECONDS), "virtual alarm delivered in process");
        AlarmTarget cancelled = new AlarmTarget();
        alarms.set(0, System.currentTimeMillis() + 500L, cancelled, "guest.alarm");
        alarms.remove(cancelled, "guest.alarm");
        require(!cancelled.latch.await(100, TimeUnit.MILLISECONDS), "virtual alarm cancellation");
        require(delegate.calls == 0, "host alarm namespace not used");
    }

    private static void testNotificationNamespace() {
        GuestIdentity identity = identity("guest.notify", 7, 9L);
        FakeNotificationDelegate delegate = new FakeNotificationDelegate();
        NotificationApi notifications = proxy(NotificationApi.class, delegate, identity, "notification");
        notifications.enqueueNotificationWithTag("guest.notify", "guest.notify", "updates", 42,
                new FakeNotification(), 7);
        require(delegate.lastId != 42, "notification ID namespaced");
        require(delegate.lastTag.startsWith("cs:u7:g9:"), "notification tag namespaced");
        int hostId = delegate.lastId;
        notifications.cancelNotificationWithTag("guest.notify", "guest.notify", "updates", 42, 7);
        require(delegate.lastId == hostId, "notification cancellation reuses host namespace ID");
    }


    private static void testNotificationFailureRollback() {
        GuestIdentity identity = identity("guest.notify.failure", 8, 10L);
        FailingNotificationDelegate delegate = new FailingNotificationDelegate();
        NotificationApi notifications = proxy(NotificationApi.class, delegate, identity, "notification");
        boolean failed = false;
        try {
            notifications.enqueueNotificationWithTag("guest.notify.failure", "guest.notify.failure",
                    "updates", 7, new FakeNotification(), 8);
        } catch (IllegalStateException expected) { failed = true; }
        require(failed, "notification delegate failure surfaced");
        require(identity.virtualServices().notifications().size() == 0,
                "failed notification does not leak namespace mapping");
        notifications.cancelNotificationWithTag("guest.notify.failure", "guest.notify.failure",
                "missing", 99, 8);
        require(delegate.cancelCalls == 0, "unknown notification cancel stays virtual");
    }

    private static void testJobNamespace() {
        GuestIdentity identity = identity("guest.jobs", 1, 5L);
        FakeJobDelegate delegate = new FakeJobDelegate();
        JobApi jobs = proxy(JobApi.class, delegate, identity, "jobscheduler");
        Job job = new Job(17);
        require(jobs.schedule(job) == 1, "job delegated");
        require(delegate.observedId != 17 && job.getId() == 17, "job ID rewritten only for host call");
        delegate.pending.add(new Job(delegate.observedId));
        List<Job> pending = jobs.getAllPendingJobs();
        require(pending.size() == 1 && pending.get(0).getId() == 17, "pending job ID restored to Guest namespace");
        jobs.cancel(17);
        require(delegate.cancelledId == delegate.observedId, "job cancellation uses stable host ID");
    }


    private static void testJobFailureRollbackAndCancelAllBoundary() {
        GuestIdentity identity = identity("guest.jobs.failure", 4, 6L);
        FailingJobDelegate delegate = new FailingJobDelegate();
        JobApi jobs = proxy(JobApi.class, delegate, identity, "jobscheduler");
        boolean failed = false;
        try { jobs.schedule(new Job(23)); } catch (IllegalStateException expected) { failed = true; }
        require(failed, "job delegate failure surfaced");
        require(identity.virtualServices().jobs().size() == 0,
                "failed job schedule does not leak namespace mapping");
        boolean blocked = false;
        try { jobs.cancelAll(); } catch (SecurityException expected) { blocked = true; }
        require(blocked && delegate.cancelAllCalls == 0,
                "job cancelAll fails closed instead of touching host namespace");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> api, T delegate, GuestIdentity identity, String service) {
        return (T) Proxy.newProxyInstance(VirtualSystemServiceSelfTest.class.getClassLoader(),
                new Class<?>[]{api}, new SystemServiceInvocationHandler(delegate, identity, service));
    }

    private static GuestIdentity identity(String packageName, int userId, long generation) {
        ApplicationInfo info = new ApplicationInfo(); info.packageName = packageName; info.uid = 12000 + userId;
        return new GuestIdentity(packageName, info.uid, info, Set.of(), "host.pkg", 10001,
                new VirtualPackageMetadata(packageName, "", info, List.of()), packageName,
                userId, generation);
    }

    interface ClipboardApi {
        void setPrimaryClip(FakeClip clip, String packageName);
        FakeClip getPrimaryClip(String packageName);
        void addPrimaryClipChangedListener(Listener listener, String packageName);
    }
    static final class FakeClipboardDelegate implements ClipboardApi {
        int calls;
        public void setPrimaryClip(FakeClip clip, String packageName) { calls++; }
        public FakeClip getPrimaryClip(String packageName) { calls++; return new FakeClip("host"); }
        public void addPrimaryClipChangedListener(Listener listener, String packageName) { calls++; }
    }
    static final class FakeClip { final String text; FakeClip(String text) { this.text = text; } }
    static final class Listener { int events; public void dispatchPrimaryClipChanged() { events++; } }

    interface AccountApi {
        Account[] getAccountsAsUser(int userId);
        boolean addAccountExplicitly(Account account, String password, Object extras);
        boolean removeAccountExplicitly(Account account);
        void setPassword(Account account, String password);
        String getPassword(Account account);
        void setAuthToken(Account account, String type, String token);
        String peekAuthToken(Account account, String type);
    }
    static final class FakeAccountDelegate implements AccountApi {
        int calls;
        private <T> T called(T value) { calls++; return value; }
        public Account[] getAccountsAsUser(int userId) { return called(new Account[]{new Account("host", "host")}); }
        public boolean addAccountExplicitly(Account account, String password, Object extras) { return called(false); }
        public boolean removeAccountExplicitly(Account account) { return called(false); }
        public void setPassword(Account account, String password) { calls++; }
        public String getPassword(Account account) { return called("host"); }
        public void setAuthToken(Account account, String type, String token) { calls++; }
        public String peekAuthToken(Account account, String type) { return called("host"); }
    }
    static final class Account {
        public final String name; public final String type;
        Account(String name, String type) { this.name = name; this.type = type; }
    }

    interface AlarmApi {
        void set(int type, long triggerAtMs, AlarmTarget target, String packageName);
        void remove(AlarmTarget target, String packageName);
    }
    static final class FakeAlarmDelegate implements AlarmApi {
        int calls;
        public void set(int type, long triggerAtMs, AlarmTarget target, String packageName) { calls++; }
        public void remove(AlarmTarget target, String packageName) { calls++; }
    }
    static final class AlarmTarget {
        final CountDownLatch latch = new CountDownLatch(1);
        public void send() { latch.countDown(); }
    }

    interface NotificationApi {
        void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
                                        FakeNotification notification, int userId);
        void cancelNotificationWithTag(String pkg, String opPkg, String tag, int id, int userId);
    }
    static class FakeNotificationDelegate implements NotificationApi {
        int lastId; String lastTag; int cancelCalls;
        public void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
                                               FakeNotification notification, int userId) {
            lastTag = tag; lastId = id;
        }
        public void cancelNotificationWithTag(String pkg, String opPkg, String tag, int id, int userId) {
            cancelCalls++; lastTag = tag; lastId = id;
        }
    }
    static final class FailingNotificationDelegate extends FakeNotificationDelegate {
        @Override public void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
                                                         FakeNotification notification, int userId) {
            throw new IllegalStateException("host notification failure");
        }
    }
    static final class FakeNotification { }

    interface JobApi {
        int schedule(Job job); List<Job> getAllPendingJobs(); void cancel(int jobId); void cancelAll();
    }
    static class FakeJobDelegate implements JobApi {
        int observedId; int cancelledId; int cancelAllCalls; final List<Job> pending = new ArrayList<>();
        public int schedule(Job job) { observedId = job.getId(); return 1; }
        public List<Job> getAllPendingJobs() { return new ArrayList<>(pending); }
        public void cancel(int jobId) { cancelledId = jobId; }
        public void cancelAll() { cancelAllCalls++; }
    }
    static final class FailingJobDelegate extends FakeJobDelegate {
        @Override public int schedule(Job job) { throw new IllegalStateException("host job failure"); }
    }
    static final class Job {
        private int mJobId;
        Job(int id) { mJobId = id; }
        public int getId() { return mJobId; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
