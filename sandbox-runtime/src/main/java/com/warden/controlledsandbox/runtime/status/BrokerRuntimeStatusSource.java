package com.warden.controlledsandbox.runtime.status;

import com.warden.controlledsandbox.runtime.component.activity.BrokerActivityRuntime;
import com.warden.controlledsandbox.runtime.component.receiver.ReceiverLifecycleCoordinator;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderLifecycleCoordinator;

import com.warden.controlledsandbox.contract.RuntimeStatusSnapshot;
import com.warden.controlledsandbox.domain.port.SessionMetricsRepository;

/** Adapts concrete Broker registries to the read-only runtime-status source port. */
public final class BrokerRuntimeStatusSource implements RuntimeStatusSource {
    private final SessionMetricsRepository sessions;
    private final BrokerActivityRuntime activity;
    private final ServiceMetricsSource services;
    private final ProviderLifecycleCoordinator providers;
    private final BrokerProviderRuntime providerAudit;
    private final ReceiverLifecycleCoordinator receivers;

    public BrokerRuntimeStatusSource(SessionMetricsRepository sessions,
                                     BrokerActivityRuntime activity,
                                     ServiceMetricsSource services,
                                     ProviderLifecycleCoordinator providers,
                                     BrokerProviderRuntime providerAudit,
                                     ReceiverLifecycleCoordinator receivers) {
        if (sessions == null || activity == null || services == null || providers == null
                || providerAudit == null || receivers == null) {
            throw new IllegalArgumentException("runtime status sources are required");
        }
        this.sessions = sessions;
        this.activity = activity;
        this.services = services;
        this.providers = providers;
        this.providerAudit = providerAudit;
        this.receivers = receivers;
    }

    @Override public RuntimeStatusSnapshot snapshot(long nowMs) {
        ProviderLifecycleCoordinator.Snapshot providerSnapshot = providers.snapshot(nowMs);
        ReceiverLifecycleCoordinator.Snapshot receiverSnapshot = receivers.snapshot();
        return RuntimeStatusSnapshot.builder()
                .slots(sessions.capacity(), sessions.used())
                .sessions(sessions.count())
                .activity(activity.pendingRouteCount(), activity.taskCount(), activity.activityCount())
                .services(services.recordCount())
                .providerResources(providerSnapshot.grants(), providerSnapshot.cursors(),
                        providerSnapshot.files(), providerSnapshot.observers(),
                        providerSnapshot.authorities(), providerSnapshot.total())
                .providerAudit(providerAudit.auditSize(), providerAudit.auditSuccessCount(),
                        providerAudit.auditFailureCount())
                .receiverResources(receiverSnapshot.dynamicRegistrations(),
                        receiverSnapshot.dynamicActionSubscriptions(),
                        receiverSnapshot.manifestPackages(), receiverSnapshot.manifestReceivers(),
                        receiverSnapshot.manifestBindings(), receiverSnapshot.actionIndexKeys(),
                        receiverSnapshot.actionIndexEntries(), receiverSnapshot.startupTemplates(),
                        receiverSnapshot.orderedPendingTokens(), receiverSnapshot.totalResources())
                .build();
    }
}
