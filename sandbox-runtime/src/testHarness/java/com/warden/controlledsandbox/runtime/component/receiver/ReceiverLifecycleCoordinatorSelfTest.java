package com.warden.controlledsandbox.runtime.component.receiver;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Cross-resource Receiver lifecycle and leak regression tests. */
public final class ReceiverLifecycleCoordinatorSelfTest {
    private ReceiverLifecycleCoordinatorSelfTest() { }

    public static void main(String[] args) throws Exception {
        disconnectPreservesManifestIndexAndDropsSessionState();
        recoveryDropsStaleStateAndRebindsCurrentGeneration();
        invalidRecoveryAndBindingFailBeforeMutation();
        instanceAndGlobalCleanupRemoveEveryReceiverResource();
        expiryUsesOrderedAuthority();
        concurrentMixedLifecycleHasNoLeaks();
        System.out.println("PASS ReceiverLifecycleCoordinatorSelfTest");
    }

    private static void disconnectPreservesManifestIndexAndDropsSessionState() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.populate(fixture.stale);
        ReceiverLifecycleCoordinator.Snapshot before = fixture.lifecycle.snapshot();
        require(before.dynamicRegistrations() == 1, "dynamic fixture missing");
        require(before.dynamicActionSubscriptions() == 1, "dynamic action fixture missing");
        require(before.manifestPackages() == 1 && before.manifestReceivers() == 1,
                "manifest fixture missing");
        require(before.manifestBindings() == 1 && before.orderedPendingTokens() == 1,
                "session Receiver resources missing");
        require(before.actionIndexKeys() == 1 && before.actionIndexEntries() == 1,
                "action index fixture missing");
        require(before.startupTemplates() == 1, "startup template fixture missing");

        ReceiverLifecycleCoordinator.CleanupResult removed = fixture.lifecycle.disconnectSession(
                fixture.stale, "GUEST_DISCONNECTED");
        require(removed.dynamicRegistrations() == 1, "dynamic disconnect cleanup");
        require(removed.manifestBindings() == 1, "manifest binding disconnect cleanup");
        require(removed.orderedTokens() == 1, "ordered token disconnect cleanup");
        require(removed.manifestPackages() == 0 && removed.manifestReceivers() == 0,
                "disconnect removed static manifest declarations");

        ReceiverLifecycleCoordinator.Snapshot after = fixture.lifecycle.snapshot();
        require(after.dynamicRegistrations() == 0 && after.manifestBindings() == 0
                && after.orderedPendingTokens() == 0, "disconnect leaked Session state");
        require(after.manifestPackages() == 1 && after.manifestReceivers() == 1
                && after.actionIndexEntries() == 1 && after.startupTemplates() == 1,
                "disconnect lost static manifest index");
    }

    private static void recoveryDropsStaleStateAndRebindsCurrentGeneration() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.populate(fixture.stale);
        ReceiverLifecycleCoordinator.RecoveryResult result = fixture.lifecycle.recoverSession(
                fixture.stale, fixture.current);
        require(result.staleResources().dynamicRegistrations() == 1,
                "recovery retained dynamic registration");
        require(result.staleResources().manifestBindings() == 1,
                "recovery retained stale manifest binding");
        require(result.staleResources().orderedTokens() == 1,
                "recovery retained stale ordered token");
        require(result.reboundBindings() == 1, "recovery did not rebind current generation");

        BrokerManifestReceiverRuntime.Route route = fixture.manifest.routeExplicit(
                explicit(), fixture.current);
        require(!route.requiresProcessStart(), "current recovered process binding missing");
        require(route.resolution().binding().orElseThrow().sessionId().equals(fixture.current.sessionId()),
                "manifest binding did not move to current Session");
        require(route.resolution().binding().orElseThrow().generation() == fixture.current.generation(),
                "manifest binding did not move to current generation");
    }

    private static void invalidRecoveryAndBindingFailBeforeMutation() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.populate(fixture.stale);
        GuestSession wrongInstance = new GuestSession("wrong", "com.example.other", USER,
                "com.example.other:receiver", 1, 2, SessionState.READY, 0, "");
        try {
            fixture.lifecycle.recoverSession(fixture.stale, wrongInstance);
            throw new AssertionError("cross-instance Receiver recovery was accepted");
        } catch (SecurityException expected) {
            require("RECEIVER_RECOVERY_IDENTITY_MISMATCH".equals(expected.getMessage()),
                    "wrong recovery identity error");
        }
        ReceiverLifecycleCoordinator.Snapshot unchanged = fixture.lifecycle.snapshot();
        require(unchanged.dynamicRegistrations() == 1 && unchanged.manifestBindings() == 1
                && unchanged.orderedPendingTokens() == 1,
                "invalid recovery mutated stale Receiver resources");

        GuestSession stopped = new GuestSession("stopped", PACKAGE, USER, PACKAGE + ":receiver",
                0, 3, SessionState.STOPPED, 0, "");
        try {
            fixture.lifecycle.bindSession(stopped);
            throw new AssertionError("stopped Session Receiver binding was accepted");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().startsWith("RECEIVER_SESSION_NOT_BINDABLE"),
                    "wrong stopped Session binding error");
        }
    }

    private static void instanceAndGlobalCleanupRemoveEveryReceiverResource() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.populate(fixture.stale);
        ReceiverLifecycleCoordinator.CleanupResult removed = fixture.lifecycle.invalidateInstance(
                fixture.stale.packageName(), fixture.stale.virtualUserId(), "INSTANCE_STOPPED");
        require(removed.dynamicRegistrations() == 1 && removed.manifestPackages() == 1
                && removed.manifestReceivers() == 1 && removed.manifestBindings() == 1
                && removed.actionIndexKeys() == 1 && removed.actionIndexEntries() == 1
                && removed.startupTemplates() == 1 && removed.orderedTokens() == 1,
                "instance cleanup counts are incomplete");
        require(fixture.lifecycle.snapshot().empty(), "instance cleanup leaked Receiver resources");

        Fixture global = Fixture.create();
        global.populate(global.stale);
        ReceiverLifecycleCoordinator.CleanupResult all = global.lifecycle.invalidateAll("BROKER_DESTROYED");
        require(all.totalRemoved() > 0 && global.lifecycle.snapshot().empty(),
                "global Receiver cleanup failed");
    }

    private static void expiryUsesOrderedAuthority() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.manifest.indexManifest(manifest(), template());
        fixture.lifecycle.bindSession(fixture.stale);
        fixture.ordered.issue(fixture.stale, RECEIVER_CLASS, 10);
        fixture.clock.now.set(11);
        require(fixture.lifecycle.purgeExpired() == 1, "expired ordered token not purged");
        require(fixture.lifecycle.snapshot().orderedPendingTokens() == 0,
                "expired ordered token remains pending");
    }

    private static void concurrentMixedLifecycleHasNoLeaks() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.manifest.indexManifest(manifest(), template());
        int workers = 24;
        List<GuestSession> sessions = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            GuestSession session = session("s-" + index, index + 1L);
            sessions.add(session);
            fixture.lifecycle.bindSession(session);
            fixture.dynamic.reserveRegistration(dynamicRequest("r-" + index), session);
            fixture.ordered.issue(session, RECEIVER_CLASS, 1_000);
        }

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            final int item = index;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    GuestSession session = sessions.get(item);
                    if ((item & 1) == 0) {
                        fixture.dynamic.resolve(ACTION, session.virtualUserId(), session.sessionId(), false);
                        fixture.manifest.routeImplicit(implicit(), session);
                    }
                    fixture.lifecycle.stopSession(session, "CONCURRENT_STOP");
                } catch (Exception error) {
                    throw new AssertionError("concurrent Receiver lifecycle", error);
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get();
        pool.shutdown();
        require(pool.awaitTermination(10, TimeUnit.SECONDS), "concurrent lifecycle timeout");

        ReceiverLifecycleCoordinator.Snapshot afterSessions = fixture.lifecycle.snapshot();
        require(afterSessions.dynamicRegistrations() == 0 && afterSessions.manifestBindings() == 0
                && afterSessions.orderedPendingTokens() == 0,
                "concurrent session cleanup leaked resources");
        require(afterSessions.manifestPackages() == 1 && afterSessions.manifestReceivers() == 1,
                "concurrent cleanup removed static package index");
        fixture.lifecycle.invalidateInstance(PACKAGE, USER, "INSTANCE_STOPPED");
        require(fixture.lifecycle.snapshot().empty(), "concurrent mixed cleanup final leak");
    }

    private static final String PACKAGE = "com.example.receiver";
    private static final int USER = 0;
    private static final String ACTION = "com.example.ACTION";
    private static final String RECEIVER_CLASS = PACKAGE + ".ManifestReceiver";

    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final AtomicInteger ids = new AtomicInteger();
        final BrokerReceiverRuntime dynamic = new BrokerReceiverRuntime();
        final BrokerManifestReceiverRuntime manifest = new BrokerManifestReceiverRuntime();
        final BrokerOrderedReceiverRuntime ordered = new BrokerOrderedReceiverRuntime(
                clock, purpose -> purpose + "-" + ids.incrementAndGet());
        final ReceiverLifecycleCoordinator lifecycle = new ReceiverLifecycleCoordinator(
                dynamic, manifest, ordered);
        final GuestSession stale = session("stale", 1);
        final GuestSession current = session("current", 2);

        static Fixture create() { return new Fixture(); }

        void populate(GuestSession session) throws Exception {
            manifest.indexManifest(manifest(), template());
            lifecycle.bindSession(session);
            dynamic.reserveRegistration(dynamicRequest("dynamic"), session);
            ordered.issue(session, RECEIVER_CLASS, 1_000);
        }
    }

    private static final class FakeClock implements Clock {
        final AtomicLong now = new AtomicLong();
        @Override public long nowMillis() { return now.get(); }
    }

    private static ManifestModel manifest() {
        ManifestModel model = new ManifestModel();
        model.packageName(PACKAGE);
        ManifestModel.Component receiver = new ManifestModel.Component(
                RECEIVER_CLASS, ":receiver", true, true, false, "", "");
        ManifestModel.IntentFilter filter = receiver.addIntentFilter(10);
        filter.addAction(ACTION);
        model.addReceiver(receiver);
        return model;
    }

    private static Bundle template() {
        Bundle bundle = new Bundle();
        bundle.putString(RuntimeKeys.PACKAGE_NAME, PACKAGE);
        bundle.putInt(RuntimeKeys.VIRTUAL_USER_ID, USER);
        bundle.putString(RuntimeKeys.APK_PATH, "/private/app.apk");
        bundle.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, "");
        bundle.putString(RuntimeKeys.APPLICATION_CLASS, "");
        bundle.putStringArrayList(RuntimeKeys.PERMISSIONS, new ArrayList<>());
        return bundle;
    }

    private static Bundle dynamicRequest(String id) {
        Bundle bundle = new Bundle();
        bundle.putString(RuntimeKeys.RECEIVER_ID, id);
        bundle.putString(RuntimeKeys.COMPONENT_CLASS, PACKAGE + ".DynamicReceiver");
        bundle.putStringArrayList(RuntimeKeys.RECEIVER_ACTIONS, new ArrayList<>(List.of(ACTION)));
        bundle.putBoolean(RuntimeKeys.RECEIVER_EXPORTED, true);
        return bundle;
    }

    private static Bundle explicit() {
        Bundle bundle = new Bundle();
        bundle.putString(RuntimeKeys.TARGET_PACKAGE_NAME, PACKAGE);
        bundle.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, USER);
        bundle.putString(RuntimeKeys.COMPONENT_CLASS, RECEIVER_CLASS);
        bundle.putString(ComponentOperations.ACTION, ACTION);
        return bundle;
    }

    private static Bundle implicit() {
        Bundle bundle = new Bundle();
        bundle.putString(ComponentOperations.ACTION, ACTION);
        return bundle;
    }

    private static GuestSession session(String id, long generation) {
        return new GuestSession(id, PACKAGE, USER, PACKAGE + ":receiver", 0,
                generation, SessionState.READY, 0, "");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
