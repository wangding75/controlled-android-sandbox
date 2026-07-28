package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.runtime.component.activity.BrokerActivityRuntime;
import com.warden.controlledsandbox.runtime.component.receiver.BrokerManifestReceiverRuntime;
import com.warden.controlledsandbox.runtime.component.receiver.BrokerReceiverRuntime;
import com.warden.controlledsandbox.runtime.component.receiver.BrokerOrderedReceiverRuntime;
import com.warden.controlledsandbox.runtime.component.receiver.ReceiverLifecycleCoordinator;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeDiagnostics;
import com.warden.controlledsandbox.runtime.provider.BrokerCursorRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerFileRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerObserverRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderLifecycleCoordinator;
import com.warden.controlledsandbox.runtime.status.BrokerRuntimeStatusSource;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.RuntimeStatusSnapshot;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import com.warden.controlledsandbox.domain.port.AuditSink;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import java.util.ArrayList;

public final class BrokerArchitecturePortsSelfTest {
    public static void main(String[] args) {
        runtimeStatusSourceUsesRepositoryPort();
        productionAdaptersAreBounded();
        System.out.println("PASS broker architecture ports");
    }

    private static void runtimeStatusSourceUsesRepositoryPort() {
        SessionRegistry sessions = new SessionRegistry(2, purpose -> purpose + "-1");
        sessions.allocate("com.example.guest", 0, 10);
        BrokerStateStore state = new BrokerStateStore();
        BrokerActivityRuntime activity = new BrokerActivityRuntime(state);
        RuntimeServiceCoordinator services = new RuntimeServiceCoordinator(state, (slot, request) -> new Bundle());
        BrokerProviderRuntime provider = new BrokerProviderRuntime();
        BrokerCursorRuntime cursors = new BrokerCursorRuntime();
        BrokerFileRuntime files = new BrokerFileRuntime();
        BrokerObserverRuntime observers = new BrokerObserverRuntime();
        ProviderLifecycleCoordinator lifecycle = new ProviderLifecycleCoordinator(
                provider, cursors, files, observers, new UriGrantRegistry());
        BrokerReceiverRuntime dynamicReceivers = new BrokerReceiverRuntime();
        BrokerManifestReceiverRuntime manifestReceivers = new BrokerManifestReceiverRuntime();
        ReceiverLifecycleCoordinator receiverLifecycle = new ReceiverLifecycleCoordinator(
                dynamicReceivers, manifestReceivers,
                new BrokerOrderedReceiverRuntime(() -> 10L, purpose -> purpose + "-token"));
        BrokerRuntimeStatusSource source = new BrokerRuntimeStatusSource(
                sessions, activity, services, lifecycle, provider, receiverLifecycle);

        RuntimeStatusSnapshot snapshot = source.snapshot(10);
        require(snapshot.slotCapacity() == 2, "slot capacity");
        require(snapshot.slotUsed() == 1, "slot used");
        require(snapshot.sessionCount() == 1, "session count");
        require(snapshot.providerResourceCount() == 0, "empty provider resources");
    }

    private static void productionAdaptersAreBounded() {
        require(new SystemMonotonicClock().nowMillis() >= 0, "monotonic clock");
        UuidTokenGenerator generator = new UuidTokenGenerator();
        String token = generator.nextToken("session");
        require(token.matches("[0-9a-f-]{36}"), "UUID token format");
        expectThrows(IllegalArgumentException.class, () -> generator.nextToken("  "),
                "blank token purpose");

        new RuntimeAuditSink().record("runtime", "status", AuditSink.Outcome.SUCCESS,
                "request\nwith-control");
        Bundle diagnostics = RuntimeDiagnostics.snapshot();
        ArrayList<String> counts = diagnostics.getStringArrayList("diagnosticsEventCounts");
        require(counts != null && counts.contains("AUDIT_RUNTIME=1"), "audit diagnostics event");
    }

    private static void expectThrows(Class<? extends Throwable> type, Runnable action, String message) {
        try { action.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError(message + ": wrong exception " + error, error);
        }
        throw new AssertionError(message + ": no exception");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private BrokerArchitecturePortsSelfTest() { }
}
