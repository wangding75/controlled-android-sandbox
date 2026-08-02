package com.warden.controlledsandbox.runtime.broker;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import com.warden.controlledsandbox.contract.IGuestProcess;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Direct executable ownership regression for RuntimeGuestConnectionPool. */
public final class RuntimeGuestConnectionPoolSelfTest {
    private RuntimeGuestConnectionPoolSelfTest() { }

    public static void main(String[] args) throws Exception {
        testDelayedDeathReconnectsWithinCurrentRequest();
        testBinderDeathCallbackDisconnectsAndRebinds();
        testConcurrentCallersShareOneReconnect();
        testConnectionIsNotPublishedBeforeDeathLinkCompletes();
        testFatalDeathLinkCleansAttemptBeforeRethrow();
        testBindTimeoutHasDistinctReasonAndRecovers();
        testDisconnectedHasDistinctReason();
        System.out.println("PASS RuntimeGuestConnectionPool direct ownership self-test");
    }

    private static void testDelayedDeathReconnectsWithinCurrentRequest() throws Exception {
        TestService service = new TestService();
        List<String> disconnects = Collections.synchronizedList(new ArrayList<>());
        RuntimeGuestConnectionPool pool = new RuntimeGuestConnectionPool(
                service, (slot, reason) -> disconnects.add(slot + ":" + reason));

        FakeGuest firstGuest = service.guest;
        Bundle first = pool.call(2, guest -> resultFor(guest, firstGuest, "first"));
        require("first".equals(first.getString("result")), "initial Guest call failed");
        require(service.bindCount == 1, "initial call did not bind exactly once");

        firstGuest.dieWithoutCallback();
        FakeGuest replacement = new FakeGuest();
        service.guest = replacement;
        Bundle rebound = pool.call(2, guest -> resultFor(guest, replacement, "rebound"));
        require("rebound".equals(rebound.getString("result")),
                "current request did not reconnect after detecting a dead cached Binder");
        require(service.bindCount == 2, "dead cached Binder did not trigger exactly one new bind");
        require(service.unbindCount == 1, "dead cached connection was not unbound exactly once");
        require(disconnects.equals(List.of("2:DEAD_BINDER")),
                "dead cached Binder reason was not reported distinctly: " + disconnects);

        firstGuest.deliverDelayedDeath();
        require(disconnects.equals(List.of("2:DEAD_BINDER")),
                "delayed old death callback affected the replacement connection");
        Bundle afterDelayedDeath = pool.call(2,
                guest -> resultFor(guest, replacement, "still-connected"));
        require("still-connected".equals(afterDelayedDeath.getString("result")),
                "replacement connection was lost after delayed old death callback");
        require(service.bindCount == 2, "delayed death caused an unnecessary third bind");

        pool.release(2);
        require(service.unbindCount == 2, "release did not unbind the replacement connection");
        pool.close();
        require(service.unbindCount == 2, "close repeated an already-owned unbind");
    }


    private static void testBinderDeathCallbackDisconnectsAndRebinds() throws Exception {
        TestService service = new TestService();
        List<String> disconnects = new ArrayList<>();
        RuntimeGuestConnectionPool pool = new RuntimeGuestConnectionPool(
                service, (slot, reason) -> disconnects.add(slot + ":" + reason));
        FakeGuest original = service.guest;
        pool.call(3, guest -> resultFor(guest, original, "warm"));
        original.dieWithCallback();
        require(disconnects.equals(List.of("3:BINDER_DIED")),
                "Binder death callback was not reported through the pool owner");
        require(service.unbindCount == 1, "Binder death callback did not release the binding");

        FakeGuest replacement = new FakeGuest();
        service.guest = replacement;
        Bundle rebound = pool.call(3, guest -> resultFor(guest, replacement, "callback-rebound"));
        require("callback-rebound".equals(rebound.getString("result")),
                "request after Binder death callback did not rebind");
        require(service.bindCount == 2, "Binder death callback recovery did not bind once");
        pool.close();
    }

    private static void testConcurrentCallersShareOneReconnect() throws Exception {
        TestService service = new TestService();
        RuntimeGuestConnectionPool pool = new RuntimeGuestConnectionPool(service, (slot, reason) -> { });
        FakeGuest original = service.guest;
        pool.call(4, guest -> resultFor(guest, original, "warm"));
        original.dieWithoutCallback();

        FakeGuest replacement = new FakeGuest();
        service.guest = replacement;
        service.blockNextConnection();
        int callerCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(callerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int index = 0; index < callerCount; index++) {
            final int caller = index;
            futures.add(executor.submit(() -> {
                start.await();
                Bundle result = pool.call(4,
                        guest -> resultFor(guest, replacement, "caller-" + caller));
                return result.getString("result");
            }));
        }
        start.countDown();
        require(service.awaitBlockedBind(), "replacement bind did not enter the controlled barrier");
        require(service.bindCount == 2,
                "concurrent callers created duplicate replacement binds before publication");
        service.releaseBlockedConnection();
        for (int index = 0; index < callerCount; index++) {
            require(("caller-" + index).equals(futures.get(index).get(5, TimeUnit.SECONDS)),
                    "concurrent caller did not receive the shared replacement Binder");
        }
        executor.shutdownNow();
        require(service.bindCount == 2,
                "concurrent dead-Binder detection did not share one in-flight reconnect");
        require(service.unbindCount == 1,
                "concurrent dead-Binder detection unbound the stale connection more than once");
        pool.close();
        require(service.unbindCount == 2, "pool close did not release the shared replacement bind");
    }


    private static void testConnectionIsNotPublishedBeforeDeathLinkCompletes() throws Exception {
        TestService service = new TestService();
        service.asyncConnect = true;
        service.guest.blockDeathLink();
        RuntimeGuestConnectionPool pool = new RuntimeGuestConnectionPool(
                service, (slot, reason) -> { }, 2_000L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstDispatched = new CountDownLatch(1);
        CountDownLatch secondDispatched = new CountDownLatch(1);
        Future<String> first = executor.submit(() -> pool.call(5, guest -> {
            firstDispatched.countDown();
            return resultFor(guest, service.guest, "first-linked");
        }).getString("result"));
        require(service.guest.awaitDeathLinkEntered(), "death registration did not enter barrier");
        Future<String> second = executor.submit(() -> pool.call(5, guest -> {
            secondDispatched.countDown();
            return resultFor(guest, service.guest, "second-linked");
        }).getString("result"));
        require(!firstDispatched.await(100, TimeUnit.MILLISECONDS),
                "first call dispatched before death registration completed");
        require(!secondDispatched.await(100, TimeUnit.MILLISECONDS),
                "second call observed Binder before death registration completed");
        service.guest.releaseDeathLink();
        require("first-linked".equals(first.get(5, TimeUnit.SECONDS)),
                "first caller did not resume after death registration");
        require("second-linked".equals(second.get(5, TimeUnit.SECONDS)),
                "second caller did not share the published connection");
        require(service.bindCount == 1, "death-link barrier caused duplicate binding");
        executor.shutdownNow();
        pool.close();
    }

    private static void testFatalDeathLinkCleansAttemptBeforeRethrow() throws Exception {
        TestService service = new TestService();
        List<String> disconnects = new ArrayList<>();
        RuntimeGuestConnectionPool pool = new RuntimeGuestConnectionPool(
                service, (slot, reason) -> disconnects.add(slot + ":" + reason));
        service.guest.failDeathLinkWithFatal();
        try {
            pool.call(3, guest -> new Bundle());
            throw new AssertionError("fatal death-link failure was converted into an ordinary bind failure");
        } catch (AssertionError expected) {
            require("fatal-death-link".equals(expected.getMessage()),
                    "unexpected fatal death-link failure: " + expected);
        }
        require(service.unbindCount == 1,
                "fatal death-link failure did not release the in-flight binding");
        require(disconnects.equals(List.of("3:DEAD_BINDER")),
                "fatal death-link failure did not publish terminal attempt state: " + disconnects);

        FakeGuest replacement = new FakeGuest();
        service.guest = replacement;
        Bundle recovered = pool.call(3, guest -> resultFor(guest, replacement, "fatal-recovered"));
        require("fatal-recovered".equals(recovered.getString("result")),
                "slot did not recover after fatal death-link cleanup");
        require(service.bindCount == 2, "fatal cleanup left a stale connection in the pool");
        pool.close();
    }

    private static void testBindTimeoutHasDistinctReasonAndRecovers() throws Exception {
        TestService service = new TestService();
        service.callbackMode = CallbackMode.NONE;
        List<String> disconnects = new ArrayList<>();
        RuntimeGuestConnectionPool pool = new RuntimeGuestConnectionPool(
                service, (slot, reason) -> disconnects.add(slot + ":" + reason), 25L);
        requireFailure("BIND_TIMEOUT", () -> pool.call(6, guest -> new Bundle()));
        require(disconnects.equals(List.of("6:BIND_TIMEOUT")),
                "bind timeout was not reported distinctly");
        require(service.unbindCount == 1, "timed-out binding was not released");

        service.callbackMode = CallbackMode.CONNECT;
        FakeGuest replacement = new FakeGuest();
        service.guest = replacement;
        Bundle recovered = pool.call(6, guest -> resultFor(guest, replacement, "recovered"));
        require("recovered".equals(recovered.getString("result")),
                "request after BIND_TIMEOUT did not create a fresh connection");
        require(service.bindCount == 2, "request after BIND_TIMEOUT did not bind again");
        pool.close();
    }

    private static void testDisconnectedHasDistinctReason() throws Exception {
        TestService service = new TestService();
        service.callbackMode = CallbackMode.NULL_BINDING;
        List<String> disconnects = new ArrayList<>();
        RuntimeGuestConnectionPool pool = new RuntimeGuestConnectionPool(
                service, (slot, reason) -> disconnects.add(slot + ":" + reason), 100L);
        requireFailure("DISCONNECTED", () -> pool.call(7, guest -> new Bundle()));
        require(disconnects.equals(List.of("7:DISCONNECTED")),
                "null/disconnected binding was not reported distinctly");
        pool.close();
    }

    private static Bundle resultFor(IGuestProcess actual, IGuestProcess expected, String value) {
        require(actual == expected, "pool published an unexpected Guest capability");
        Bundle result = new Bundle();
        result.putString("result", value);
        return result;
    }

    private static void requireFailure(String expected, ThrowingRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected failure " + expected);
        } catch (IllegalStateException error) {
            require(expected.equals(error.getMessage()),
                    "expected " + expected + " but was " + error.getMessage());
        }
    }

    private enum CallbackMode { CONNECT, NONE, NULL_BINDING }

    private static final class TestService extends Service {
        private volatile FakeGuest guest = new FakeGuest();
        private volatile CallbackMode callbackMode = CallbackMode.CONNECT;
        private volatile int bindCount;
        private volatile int unbindCount;
        private volatile boolean asyncConnect;
        private volatile CountDownLatch blockedBindEntered;
        private volatile CountDownLatch blockedBindRelease;

        void blockNextConnection() {
            blockedBindEntered = new CountDownLatch(1);
            blockedBindRelease = new CountDownLatch(1);
        }

        boolean awaitBlockedBind() throws InterruptedException {
            CountDownLatch latch = blockedBindEntered;
            return latch != null && latch.await(5, TimeUnit.SECONDS);
        }

        void releaseBlockedConnection() {
            CountDownLatch latch = blockedBindRelease;
            if (latch != null) latch.countDown();
        }

        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            bindCount++;
            CountDownLatch entered = blockedBindEntered;
            CountDownLatch release = blockedBindRelease;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("controlled bind barrier timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                } finally {
                    blockedBindEntered = null;
                    blockedBindRelease = null;
                }
            }
            if (callbackMode == CallbackMode.CONNECT) {
                ComponentName component = new ComponentName(
                        "com.warden.controlledsandbox", "Guest" + bindCount);
                if (asyncConnect) {
                    Thread callback = new Thread(
                            () -> connection.onServiceConnected(component, guest),
                            "guest-connect-callback");
                    callback.setDaemon(true);
                    callback.start();
                } else {
                    connection.onServiceConnected(component, guest);
                }
            } else if (callbackMode == CallbackMode.NULL_BINDING) {
                connection.onNullBinding(new ComponentName(
                        "com.warden.controlledsandbox", "Guest" + bindCount));
            }
            return true;
        }

        @Override public void unbindService(ServiceConnection connection) {
            unbindCount++;
        }
    }

    private static final class FakeGuest extends IGuestProcess.Stub {
        private IBinder.DeathRecipient recipient;
        private IBinder.DeathRecipient delayedRecipient;
        private volatile boolean alive = true;
        private volatile CountDownLatch deathLinkEntered;
        private volatile CountDownLatch deathLinkRelease;
        private volatile boolean fatalOnDeathLink;

        void blockDeathLink() {
            deathLinkEntered = new CountDownLatch(1);
            deathLinkRelease = new CountDownLatch(1);
        }

        void failDeathLinkWithFatal() {
            fatalOnDeathLink = true;
        }

        boolean awaitDeathLinkEntered() throws InterruptedException {
            CountDownLatch latch = deathLinkEntered;
            return latch != null && latch.await(5, TimeUnit.SECONDS);
        }

        void releaseDeathLink() {
            CountDownLatch latch = deathLinkRelease;
            if (latch != null) latch.countDown();
        }

        @Override public RuntimeOperationResult executeV2(RuntimeOperationRequest request) {
            return null;
        }

        @Override public void shutdown(String sessionId, long generation) { }

        @Override public boolean isBinderAlive() { return alive; }

        @Override public void linkToDeath(IBinder.DeathRecipient value, int flags)
                throws RemoteException {
            CountDownLatch entered;
            CountDownLatch release;
            synchronized (this) {
                if (fatalOnDeathLink) throw new AssertionError("fatal-death-link");
                if (!alive) throw new RemoteException("dead");
                entered = deathLinkEntered;
                release = deathLinkRelease;
            }
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new RemoteException("death-link barrier timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new RemoteException("death-link barrier interrupted");
                }
            }
            synchronized (this) {
                if (!alive) throw new RemoteException("dead");
                recipient = value;
                deathLinkEntered = null;
                deathLinkRelease = null;
            }
        }

        @Override public synchronized boolean unlinkToDeath(IBinder.DeathRecipient value, int flags) {
            if (recipient != value) return false;
            recipient = null;
            return true;
        }

        synchronized void dieWithoutCallback() {
            alive = false;
            delayedRecipient = recipient;
        }

        synchronized void deliverDelayedDeath() {
            IBinder.DeathRecipient value = delayedRecipient;
            delayedRecipient = null;
            if (value != null) value.binderDied();
        }

        synchronized void dieWithCallback() {
            alive = false;
            IBinder.DeathRecipient value = recipient;
            if (value != null) value.binderDied();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
