package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.IProviderObserver;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Cross-resource Provider lifecycle and leak regression tests. */
public final class ProviderLifecycleCoordinatorSelfTest {
    private ProviderLifecycleCoordinatorSelfTest() { }

    public static void main(String[] args) throws Exception {
        disconnectPreservesAuthorityAndRevokesCapabilities();
        terminalDisconnectRemovesAuthority();
        recoveryRebindsAuthorityAndRevokesStaleCapabilities();
        instanceAndExpiryCleanup();
        concurrentCleanupIsIdempotent();
        concurrentMixedCleanupHasNoLeaks();
        System.out.println("PASS ProviderLifecycleCoordinatorSelfTest");
    }

    private static void disconnectPreservesAuthorityAndRevokesCapabilities() throws Exception {
        Fixture fixture = Fixture.full(0);
        ProviderLifecycleCoordinator.Snapshot before = fixture.lifecycle.snapshot(1);
        check(before.authorities() == 1 && before.observers() == 1 && before.grants() == 1
                && before.cursors() == 1 && before.files() == 1, "fixture resources missing");

        GuestSession recovering = new GuestSession(fixture.provider.sessionId(), fixture.provider.packageName(),
                fixture.provider.virtualUserId(), fixture.provider.processName(), fixture.provider.processSlot(),
                fixture.provider.generation(), SessionState.RECOVERING, 1, "binder died");
        ProviderLifecycleCoordinator.CleanupResult cleanup =
                fixture.lifecycle.disconnectSession(recovering);
        check(cleanup.authorities() == 0, "disconnect removed recoverable authority");
        check(cleanup.observers() == 1 && cleanup.grants() == 0
                && cleanup.cursorsRemoved() == 1 && cleanup.filesRemoved() == 1,
                "disconnect did not revoke process-bound generation capabilities");
        check(fixture.descriptor.isClosed(), "disconnect did not close Broker FD resource");
        ProviderLifecycleCoordinator.Snapshot after = fixture.lifecycle.snapshot(2);
        check(after.authorities() == 1 && after.grants() == 1 && after.total() == 2,
                "disconnect did not preserve recoverable URI permission");

        ProviderLifecycleCoordinator.CleanupResult stop = fixture.lifecycle.stopSession(fixture.provider);
        check(stop.authorities() == 1 && fixture.lifecycle.snapshot(3).empty(),
                "explicit stop did not remove preserved authority");
    }


    private static void terminalDisconnectRemovesAuthority() throws Exception {
        Fixture fixture = Fixture.full(5);
        GuestSession failed = new GuestSession(fixture.provider.sessionId(), fixture.provider.packageName(),
                fixture.provider.virtualUserId(), fixture.provider.processName(), fixture.provider.processSlot(),
                fixture.provider.generation(), SessionState.FAILED, 2, "prepare failed");
        ProviderLifecycleCoordinator.CleanupResult cleanup = fixture.lifecycle.disconnectSession(failed);
        check(cleanup.authorities() == 1 && cleanup.totalRemoved() == 5,
                "terminal disconnect preserved Provider authority");
        check(fixture.lifecycle.snapshot(3).empty(), "terminal disconnect leaked Provider resources");
    }

    private static void recoveryRebindsAuthorityAndRevokesStaleCapabilities() throws Exception {
        Fixture fixture = Fixture.full(1);
        GuestSession recovered = session("provider-current", "com.provider", 1, 2, 1);
        ProviderLifecycleCoordinator.RecoveryResult recovery =
                fixture.lifecycle.recoverSession(fixture.provider, recovered);
        check(recovery.authoritiesRebound() == 1 && recovery.grantsRebound() == 1,
                "Provider authority/URI permission was not rebound");
        check(recovery.staleResources().observers() == 1
                && recovery.staleResources().grants() == 0
                && recovery.staleResources().cursorsRemoved() == 1
                && recovery.staleResources().filesRemoved() == 1,
                "recovery did not revoke stale process capabilities");
        check(fixture.descriptor.isClosed(), "recovery leaked Broker FD resource");

        fixture.providers.requireOwned(operation("provider.authority"), recovered);
        boolean staleDenied = false;
        try { fixture.providers.requireOwned(operation("provider.authority"), fixture.provider); }
        catch (SecurityException expected) { staleDenied = true; }
        check(staleDenied, "stale Provider generation retained authority");
        check(fixture.lifecycle.snapshot(2).total() == 2,
                "recovery did not retain the rebound URI permission");
        check(fixture.lifecycle.stopSession(recovered).authorities() == 1
                && fixture.lifecycle.snapshot(3).empty(), "recovered Provider cleanup failed");
    }

    private static void instanceAndExpiryCleanup() throws Exception {
        Fixture instance = Fixture.full(2);
        ProviderLifecycleCoordinator.CleanupResult cleanup =
                instance.lifecycle.invalidateInstance("com.provider", 2);
        check(cleanup.totalRemoved() == 5 && instance.lifecycle.snapshot(2).empty(),
                "instance cleanup did not remove all Provider resources");
        check(instance.descriptor.isClosed(), "instance cleanup leaked FD");

        Fixture expiring = Fixture.full(3, 1L);
        ProviderLifecycleCoordinator.CleanupResult expired =
                expiring.lifecycle.purgeExpired(BrokerCursorRuntime.LEASE_TTL_MS + 1L);
        check(expired.grants() == 1 && expired.cursorsRemoved() == 1 && expired.filesRemoved() == 1,
                "expiry purge did not remove all time-bounded capabilities");
        check(expiring.descriptor.isClosed(), "expiry purge leaked FD");
        ProviderLifecycleCoordinator.Snapshot remaining = expiring.lifecycle.snapshot(
                BrokerCursorRuntime.LEASE_TTL_MS + 2L);
        check(remaining.authorities() == 1 && remaining.observers() == 1 && remaining.total() == 2,
                "expiry purge removed non-expiring resources");
        expiring.lifecycle.invalidateInstance("com.provider", 3);
        expiring.lifecycle.invalidateInstance("com.caller", 3);
        check(expiring.lifecycle.snapshot(BrokerCursorRuntime.LEASE_TTL_MS + 3L).empty(),
                "post-expiry instance cleanup leaked resources");
    }

    private static void concurrentCleanupIsIdempotent() throws Exception {
        Fixture fixture = Fixture.full(4);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger removed = new AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < workers; index++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    removed.addAndGet(fixture.lifecycle.stopSession(fixture.provider).totalRemoved());
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();
        check(failures.isEmpty(), "concurrent cleanup failed: " + failures);
        check(removed.get() == 5, "Provider resources were removed more than once or leaked");
        check(fixture.lifecycle.snapshot(2).empty(), "concurrent cleanup leaked Provider resources");
        check(fixture.descriptor.isClosed(), "concurrent cleanup leaked FD");
    }


    private static void concurrentMixedCleanupHasNoLeaks() throws Exception {
        Fixture fixture = Fixture.full(6);
        GuestSession recovering = new GuestSession(fixture.provider.sessionId(), fixture.provider.packageName(),
                fixture.provider.virtualUserId(), fixture.provider.processName(), fixture.provider.processSlot(),
                fixture.provider.generation(), SessionState.RECOVERING, 1, "binder died");
        int workers = 24;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger removed = new AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < workers; index++) {
            final int operation = index % 4;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    ProviderLifecycleCoordinator.CleanupResult result;
                    if (operation == 0) {
                        result = fixture.lifecycle.disconnectSession(recovering);
                    } else if (operation == 1) {
                        result = fixture.lifecycle.stopSession(fixture.provider);
                    } else if (operation == 2) {
                        result = fixture.lifecycle.invalidateInstance("com.provider", 6);
                    } else {
                        result = fixture.lifecycle.purgeExpired(BrokerCursorRuntime.LEASE_TTL_MS + 1L);
                    }
                    removed.addAndGet(result.totalRemoved());
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();
        check(failures.isEmpty(), "mixed Provider cleanup failed: " + failures);
        check(removed.get() == 5, "mixed Provider cleanup duplicated or leaked resources: " + removed.get());
        check(fixture.lifecycle.snapshot(BrokerCursorRuntime.LEASE_TTL_MS + 2L).empty(),
                "mixed Provider cleanup leaked resources");
        check(fixture.descriptor.isClosed(), "mixed Provider cleanup leaked FD");
    }

    private static final class Fixture {
        final BrokerProviderRuntime providers = new BrokerProviderRuntime();
        final BrokerCursorRuntime cursors = new BrokerCursorRuntime();
        final BrokerFileRuntime files = new BrokerFileRuntime();
        final BrokerObserverRuntime observers = new BrokerObserverRuntime();
        final UriGrantRegistry grants = new UriGrantRegistry();
        final ProviderLifecycleCoordinator lifecycle = new ProviderLifecycleCoordinator(
                providers, cursors, files, observers, grants);
        final GuestSession provider;
        final GuestSession caller;
        final ParcelFileDescriptor descriptor;

        private Fixture(int userId, long grantTtlMs) throws Exception {
            provider = session("provider-session-" + userId, "com.provider", userId, 1, 1);
            caller = session("caller-session-" + userId, "com.caller", userId, 1, 2);
            providers.reservePrepare(prepare(), provider);

            Bundle observerRequest = new Bundle();
            observerRequest.putString(RuntimeKeys.OBSERVER_ID, "observer-" + userId);
            observerRequest.putBinder(RuntimeKeys.OBSERVER_CALLBACK, new NoOpObserver().asBinder());
            observerRequest.putBoolean(RuntimeKeys.OBSERVER_NOTIFY_DESCENDANTS, true);
            observers.register(observerRequest, instance(caller), caller.sessionId(), caller.generation(),
                    instance(provider), provider.sessionId(), provider.generation(), userId,
                    "provider.authority", "content://provider.authority/items");

            grants.grant(instance(provider), provider.sessionId(), provider.generation(),
                    instance(caller), caller.sessionId(), caller.generation(), userId,
                    "content://provider.authority", 1, false, 0, grantTtlMs);

            BrokerCursorRuntime.QueryReservation cursor = cursors.reserveQuery(
                    instance(caller), caller.sessionId(), caller.generation(), instance(provider),
                    provider.packageName(), userId, provider.processName(), provider.sessionId(),
                    provider.generation(), "content://provider.authority/items", 1, 0);
            Bundle cursorResult = new Bundle();
            cursorResult.putString(RuntimeKeys.CURSOR_TOKEN, cursor.token());
            cursorResult.putInt(RuntimeKeys.CURSOR_TOTAL_ROWS, 0);
            cursorResult.putInt(RuntimeKeys.CURSOR_NEXT_OFFSET, 0);
            cursorResult.putLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, 0);
            cursorResult.putBoolean(RuntimeKeys.CURSOR_END_REACHED, true);
            cursors.commitQuery(cursor, cursorResult, 1);

            BrokerFileRuntime.OpenReservation file = files.reserveOpen(
                    ComponentOperations.PROVIDER_OPEN_FILE, instance(caller), caller.sessionId(),
                    caller.generation(), instance(provider), provider.packageName(), userId,
                    provider.processName(), provider.sessionId(), provider.generation(),
                    "content://provider.authority/file", 1, "r", "", 0);
            descriptor = new ParcelFileDescriptor();
            Bundle fileResult = new Bundle();
            fileResult.putString(RuntimeKeys.FILE_TOKEN, file.token());
            fileResult.putString(RuntimeKeys.FILE_DESCRIPTOR_KIND, "FILE");
            fileResult.putString(RuntimeKeys.PROVIDER_FILE_MODE, "r");
            fileResult.putString(RuntimeKeys.PROVIDER_MIME_TYPE, "");
            fileResult.putParcelable(RuntimeKeys.FILE_DESCRIPTOR, descriptor);
            fileResult.putLong(RuntimeKeys.FILE_START_OFFSET, 0L);
            fileResult.putLong(RuntimeKeys.FILE_DECLARED_LENGTH, -1L);
            files.commitOpen(file, fileResult, 1);
        }

        static Fixture full(int userId) throws Exception {
            return new Fixture(userId, 60_000L);
        }

        static Fixture full(int userId, long grantTtlMs) throws Exception {
            return new Fixture(userId, grantTtlMs);
        }
    }

    private static Bundle prepare() {
        Bundle value = new Bundle();
        value.putString(ComponentOperations.AUTHORITY, "provider.authority");
        value.putString(RuntimeKeys.COMPONENT_CLASS, "com.provider.DataProvider");
        value.putBoolean(RuntimeKeys.PROVIDER_EXPORTED, false);
        return value;
    }

    private static Bundle operation(String authority) {
        Bundle value = new Bundle();
        value.putString(ComponentOperations.AUTHORITY, authority);
        value.putString(RuntimeKeys.URI, "content://" + authority + "/items");
        return value;
    }

    private static GuestSession session(String id, String packageName, int userId,
                                        long generation, int slot) {
        return new GuestSession(id, packageName, userId, packageName + ":provider", slot,
                generation, SessionState.READY, 0, "");
    }

    private static String instance(GuestSession session) {
        return BrokerProviderRuntime.instanceId(session.packageName(), session.virtualUserId());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class NoOpObserver extends IProviderObserver.Stub {
        @Override public void onChange(String uri, boolean selfChange, int flags) { }
    }
}
