package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.IProviderObserver;

public final class BrokerObserverRuntimeSelfTest {
    public static void main(String[] args) {
        testRegistrationDeliveryAndIsolation();
        testOwnershipAndLifecycle();
        testCallbackFailureAndTargetDeath();
        testImmediateDeathDuringRegistration();
        testConcurrentIdempotentRegistration();
        System.out.println("PASS BrokerObserverRuntimeSelfTest");
    }

    private static void testRegistrationDeliveryAndIsolation() {
        BrokerObserverRuntime runtime = new BrokerObserverRuntime();
        RecordingObserver callback = new RecordingObserver();
        Bundle request = request("observer-a", callback, true, false);
        BrokerObserverRuntime.RegisterResult first = runtime.register(request, "u0:caller",
                "caller-session", 1, "u0:provider", "provider-session", 4, 0,
                "pkg.data", "content://pkg.data/items");
        check(first.created(), "observer not created");
        check(!runtime.register(request, "u0:caller", "caller-session", 1,
                "u0:provider", "provider-session", 4, 0, "pkg.data",
                "content://pkg.data/items").created(), "observer registration not idempotent");

        BrokerObserverRuntime.NotifyResult delivered = runtime.notifyChange(0, "pkg.data",
                "content://pkg.data/items/42", "u0:provider", "provider-session", 4, 7);
        check(delivered.matched() == 1 && delivered.delivered() == 1,
                "observer descendant delivery failed");
        check(callback.calls == 1 && callback.lastFlags == 7
                && "content://pkg.data/items/42".equals(callback.lastUri),
                "observer callback payload mismatch");

        BrokerObserverRuntime.NotifyResult isolated = runtime.notifyChange(1, "pkg.data",
                "content://pkg.data/items/42", "u1:provider", "provider-session", 4, 0);
        check(isolated.matched() == 0 && callback.calls == 1, "observer crossed virtual users");

        RecordingObserver conflicting = new RecordingObserver();
        boolean callbackConflict = false;
        try {
            runtime.register(request("observer-a", conflicting, true, false), "u0:caller",
                    "caller-session", 1, "u0:provider", "provider-session", 4, 0,
                    "pkg.data", "content://pkg.data/items");
        } catch (SecurityException expected) {
            callbackConflict = true;
        }
        check(callbackConflict, "observer callback replacement accepted");
    }

    private static void testOwnershipAndLifecycle() {
        BrokerObserverRuntime runtime = new BrokerObserverRuntime();
        RecordingObserver selfSuppressed = new RecordingObserver();
        runtime.register(request("self-off", selfSuppressed, false, false), "u2:provider",
                "provider-session", 8, "u2:provider", "provider-session", 8, 2,
                "pkg.self", "content://pkg.self/items");
        check(runtime.notifyChange(2, "pkg.self", "content://pkg.self/items", "u2:provider",
                "provider-session", 8, 0).matched() == 0, "self notification not suppressed");

        RecordingObserver selfAllowed = new RecordingObserver();
        runtime.register(request("self-on", selfAllowed, false, true), "u2:provider",
                "provider-session", 8, "u2:provider", "provider-session", 8, 2,
                "pkg.self", "content://pkg.self/items");
        check(runtime.notifyChange(2, "pkg.self", "content://pkg.self/items", "u2:provider",
                "provider-session", 8, 0).delivered() == 1 && selfAllowed.selfChange,
                "self notification delivery failed");

        boolean wrongOwner = false;
        try { runtime.unregister("self-on", "u2:other", "provider-session", 8); }
        catch (SecurityException expected) { wrongOwner = true; }
        check(wrongOwner, "observer unregister owner mismatch accepted");
        check(runtime.invalidateSession("provider-session", 8) == 2,
                "observer session cleanup count");
        check(runtime.size() == 0, "observer session cleanup leaked registrations");
    }

    private static void testCallbackFailureAndTargetDeath() {
        BrokerObserverRuntime runtime = new BrokerObserverRuntime();
        FailingObserver failing = new FailingObserver();
        runtime.register(request("observer-failing", failing, true, false), "u0:caller",
                "caller-session", 1, "u0:provider", "provider-session", 2, 0,
                "pkg.data", "content://pkg.data/items");
        BrokerObserverRuntime.NotifyResult failed = runtime.notifyChange(0, "pkg.data",
                "content://pkg.data/items/1", "u0:provider", "provider-session", 2, 0);
        check(failed.matched() == 1 && failed.delivered() == 0
                && failed.failures().size() == 1 && runtime.size() == 0,
                "failed observer callback was not removed");

        RecordingObserver callback = new RecordingObserver();
        runtime.register(request("observer-target-death", callback, true, false), "u0:caller",
                "caller-session", 1, "u0:provider", "provider-session", 3, 0,
                "pkg.data", "content://pkg.data/items");
        check(runtime.invalidateSession("provider-session", 3) == 1 && runtime.size() == 0,
                "target Provider session cleanup failed");
    }


    private static void testImmediateDeathDuringRegistration() {
        BrokerObserverRuntime runtime = new BrokerObserverRuntime();
        ImmediateDeathObserver callback = new ImmediateDeathObserver();
        boolean rejected = false;
        try {
            runtime.register(request("observer-immediate-death", callback, true, false),
                    "u0:caller", "caller-session", 1, "u0:provider",
                    "provider-session", 4, 0, "pkg.data", "content://pkg.data/items");
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("DEAD_DURING_LINK");
        }
        check(rejected, "observer that died inside linkToDeath was published");
        check(runtime.size() == 0, "immediately dead observer leaked into authority registry");
    }

    private static void testConcurrentIdempotentRegistration() {
        BrokerObserverRuntime runtime = new BrokerObserverRuntime();
        RecordingObserver callback = new RecordingObserver();
        Bundle request = request("observer-concurrent", callback, true, false);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(16);
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        for (int i = 0; i < 16; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    if (runtime.register(request, "u0:caller", "caller-session", 1,
                            "u0:provider", "provider-session", 4, 0, "pkg.data",
                            "content://pkg.data/items").created()) {
                        created.incrementAndGet();
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        start.countDown();
        try { done.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
        if (failure.get() != null) throw new AssertionError(failure.get());
        check(created.get() == 1 && runtime.size() == 1,
                "concurrent observer registration was not single-create idempotent");
    }

    private static Bundle request(String id, RecordingObserver callback,
                                  boolean descendants, boolean deliverSelf) {
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.OBSERVER_ID, id);
        request.putBinder(RuntimeKeys.OBSERVER_CALLBACK, callback.asBinder());
        request.putBoolean(RuntimeKeys.OBSERVER_NOTIFY_DESCENDANTS, descendants);
        request.putBoolean(RuntimeKeys.OBSERVER_DELIVER_SELF, deliverSelf);
        return request;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ImmediateDeathObserver extends RecordingObserver {
        private boolean alive = true;

        @Override public boolean isBinderAlive() { return alive; }

        @Override public void linkToDeath(android.os.IBinder.DeathRecipient recipient, int flags) {
            alive = false;
            recipient.binderDied();
        }

        @Override public boolean unlinkToDeath(android.os.IBinder.DeathRecipient recipient, int flags) {
            return true;
        }
    }

    private static final class FailingObserver extends RecordingObserver {
        @Override public void onChange(String uri, boolean selfChange, int flags)
                throws android.os.RemoteException {
            throw new android.os.RemoteException("dead observer");
        }
    }

    private static class RecordingObserver extends IProviderObserver.Stub {
        int calls;
        String lastUri;
        int lastFlags;
        boolean selfChange;

        @Override public void onChange(String uri, boolean selfChange, int flags)
                throws android.os.RemoteException {
            calls++;
            lastUri = uri;
            lastFlags = flags;
            this.selfChange = selfChange;
        }
    }
}
