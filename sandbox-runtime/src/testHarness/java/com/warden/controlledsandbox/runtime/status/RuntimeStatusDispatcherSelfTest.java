package com.warden.controlledsandbox.runtime.status;

import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.RuntimeStatusSnapshot;
import com.warden.controlledsandbox.domain.port.AuditSink;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class RuntimeStatusDispatcherSelfTest {
    public static void main(String[] args) {
        successUsesInjectedPorts();
        validationStopsBeforeMaintenance();
        sourceFailureIsStable();
        auditFailureDoesNotAlterResult();
        concurrentDispatchIsStateless();
        dependencyValidation();
        System.out.println("PASS runtime-status dispatcher");
    }

    private static void successUsesInjectedPorts() {
        AtomicLong maintainedAt = new AtomicLong(-1);
        AtomicLong sourcedAt = new AtomicLong(-1);
        List<String> audit = new ArrayList<>();
        RuntimeStatusDispatcher dispatcher = new RuntimeStatusDispatcher(
                () -> 1234L,
                now -> {
                    sourcedAt.set(now);
                    return snapshot();
                },
                maintainedAt::set,
                (category, action, outcome, detail) ->
                        audit.add(category + ":" + action + ":" + outcome + ":" + detail));
        RuntimeStatusResult result = dispatcher.dispatch(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT, "dispatch-success"));
        require(result.successful(), "typed success");
        require(maintainedAt.get() == 1234L, "maintenance clock");
        require(sourcedAt.get() == 1234L, "source clock");
        require(audit.size() == 1 && audit.get(0).contains("SUCCESS"), "success audit");
    }

    private static void validationStopsBeforeMaintenance() {
        AtomicInteger maintenance = new AtomicInteger();
        AtomicInteger source = new AtomicInteger();
        List<AuditSink.Outcome> outcomes = new ArrayList<>();
        RuntimeStatusDispatcher dispatcher = new RuntimeStatusDispatcher(
                () -> 1L,
                now -> { source.incrementAndGet(); return snapshot(); },
                now -> maintenance.incrementAndGet(),
                (category, action, outcome, detail) -> outcomes.add(outcome));
        RuntimeStatusResult result = dispatcher.dispatch(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT + 1, "future"));
        require(!result.successful(), "future protocol rejected");
        require("UNSUPPORTED_PROTOCOL".equals(result.error().code()), "stable validation error");
        require(maintenance.get() == 0 && source.get() == 0, "no work after validation rejection");
        require(outcomes.equals(List.of(AuditSink.Outcome.REJECTED)), "rejected audit");
    }

    private static void sourceFailureIsStable() {
        List<AuditSink.Outcome> outcomes = new ArrayList<>();
        RuntimeStatusDispatcher dispatcher = new RuntimeStatusDispatcher(
                () -> 5L,
                now -> { throw new IllegalStateException("source failed"); },
                now -> { },
                (category, action, outcome, detail) -> outcomes.add(outcome));
        RuntimeStatusResult result = dispatcher.dispatch(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT, "source-failure"));
        require(!result.successful(), "source failure result");
        require("INTERNAL_ERROR".equals(result.error().code()), "source failure code");
        require(outcomes.equals(List.of(AuditSink.Outcome.FAILURE)), "failure audit");
    }

    private static void auditFailureDoesNotAlterResult() {
        RuntimeStatusDispatcher dispatcher = new RuntimeStatusDispatcher(
                () -> 7L,
                now -> snapshot(),
                now -> { },
                (category, action, outcome, detail) -> { throw new IllegalStateException("audit unavailable"); });
        RuntimeStatusResult result = dispatcher.dispatch(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT, "audit-failure"));
        require(result.successful(), "audit failure isolated");
    }

    private static void concurrentDispatchIsStateless() {
        AtomicInteger maintenance = new AtomicInteger();
        AtomicInteger source = new AtomicInteger();
        AtomicInteger audit = new AtomicInteger();
        RuntimeStatusDispatcher dispatcher = new RuntimeStatusDispatcher(
                () -> 99L,
                now -> { source.incrementAndGet(); return snapshot(); },
                now -> maintenance.incrementAndGet(),
                (category, action, outcome, detail) -> audit.incrementAndGet());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        List<java.util.concurrent.Future<RuntimeStatusResult>> results = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            final int index = i;
            results.add(pool.submit(() -> dispatcher.dispatch(
                    new RuntimeStatusRequest(RuntimeProtocol.CURRENT, "concurrent-" + index))));
        }
        for (java.util.concurrent.Future<RuntimeStatusResult> future : results) {
            try { require(future.get().successful(), "concurrent result"); }
            catch (Exception error) { throw new AssertionError("concurrent dispatch", error); }
        }
        pool.shutdownNow();
        require(maintenance.get() == 64, "concurrent maintenance count");
        require(source.get() == 64, "concurrent source count");
        require(audit.get() == 64, "concurrent audit count");
    }

    private static void dependencyValidation() {
        expectThrows(IllegalArgumentException.class,
                () -> new RuntimeStatusDispatcher(null, now -> snapshot(), now -> { },
                        (category, action, outcome, detail) -> { }),
                "null clock");
        RuntimeStatusDispatcher negativeClock = new RuntimeStatusDispatcher(
                () -> -1L, now -> snapshot(), now -> { },
                (category, action, outcome, detail) -> { });
        RuntimeStatusResult result = negativeClock.dispatch(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT, "negative-clock"));
        require(!result.successful() && "INTERNAL_ERROR".equals(result.error().code()),
                "negative clock fail closed");
    }

    private static RuntimeStatusSnapshot snapshot() {
        return RuntimeStatusSnapshot.builder()
                .slots(8, 2)
                .sessions(2)
                .activity(1, 2, 3)
                .services(4)
                .providerResources(1, 1, 1, 1, 1, 5)
                .providerAudit(2, 3, 4)
                .dynamicReceivers(1)
                .manifestReceivers(1, 2, 1)
                .build();
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

    private RuntimeStatusDispatcherSelfTest() { }
}
