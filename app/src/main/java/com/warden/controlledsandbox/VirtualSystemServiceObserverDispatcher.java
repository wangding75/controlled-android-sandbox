package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/** Best-effort observer delivery that never changes the result of an already committed mutation. */
final class VirtualSystemServiceObserverDispatcher {
    @FunctionalInterface
    interface ObserverSource {
        List<IVirtualSystemServiceObserver> snapshot(VirtualSystemServiceStore.Scope scope);
    }

    @FunctionalInterface
    interface ObserverCall {
        void invoke(IVirtualSystemServiceObserver observer) throws Exception;
    }

    private final ScheduledExecutorService scheduler;
    private final ObserverSource source;
    private final Consumer<String> warningSink;

    VirtualSystemServiceObserverDispatcher(ScheduledExecutorService scheduler,
            ObserverSource source, Consumer<String> warningSink) {
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.warningSink = java.util.Objects.requireNonNull(warningSink, "warningSink");
    }

    void dispatch(String operation, VirtualSystemServiceStore.Scope scope, ObserverCall call) {
        if (scope == null) return;
        try {
            scheduler.execute(() -> {
                for (IVirtualSystemServiceObserver observer : source.snapshot(scope)) {
                    try { call.invoke(observer); }
                    catch (Exception ignored) { }
                }
            });
        } catch (RuntimeException rejected) {
            warningSink.accept("VIRTUAL_OBSERVER_DISPATCH_FAILED:" + operation + ":"
                    + rejected.getClass().getSimpleName());
        }
    }
}
