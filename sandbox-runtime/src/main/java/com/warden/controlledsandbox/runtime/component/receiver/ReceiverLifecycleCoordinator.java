package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.domain.component.receiver.DynamicReceiverRegistry;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;

/**
 * Single Broker authority for cross-registry Receiver lifecycle transitions.
 *
 * <p>Static Manifest declarations survive process loss, while dynamic registrations,
 * process bindings and Ordered completion tokens are Session/generation scoped.</p>
 */
public final class ReceiverLifecycleCoordinator {
    public record CleanupResult(int dynamicRegistrations, int manifestBindings,
                                int orderedTokens, int manifestPackages,
                                int manifestReceivers, int actionIndexKeys,
                                int actionIndexEntries, int startupTemplates) {
        public CleanupResult {
            if (dynamicRegistrations < 0 || manifestBindings < 0 || orderedTokens < 0
                    || manifestPackages < 0 || manifestReceivers < 0 || actionIndexKeys < 0
                    || actionIndexEntries < 0 || startupTemplates < 0) {
                throw new IllegalArgumentException("Receiver cleanup counts must be non-negative");
            }
        }

        public int totalRemoved() {
            return checkedTotal(dynamicRegistrations, manifestBindings, orderedTokens,
                    manifestPackages, manifestReceivers, actionIndexKeys,
                    actionIndexEntries, startupTemplates);
        }
    }

    public record RecoveryResult(CleanupResult staleResources, int reboundBindings) {
        public RecoveryResult {
            if (staleResources == null || reboundBindings < 0) {
                throw new IllegalArgumentException("Receiver recovery result is invalid");
            }
        }
    }

    public record Snapshot(int dynamicRegistrations, int dynamicActionSubscriptions,
                           int manifestPackages, int manifestReceivers,
                           int manifestBindings, int actionIndexKeys,
                           int actionIndexEntries, int startupTemplates,
                           int orderedPendingTokens) {
        public Snapshot {
            if (dynamicRegistrations < 0 || dynamicActionSubscriptions < 0
                    || manifestPackages < 0 || manifestReceivers < 0 || manifestBindings < 0
                    || actionIndexKeys < 0 || actionIndexEntries < 0 || startupTemplates < 0
                    || orderedPendingTokens < 0) {
                throw new IllegalArgumentException("Receiver snapshot counts must be non-negative");
            }
        }

        public int totalResources() {
            return checkedTotal(dynamicRegistrations, dynamicActionSubscriptions,
                    manifestPackages, manifestReceivers, manifestBindings, actionIndexKeys,
                    actionIndexEntries, startupTemplates, orderedPendingTokens);
        }

        public boolean empty() { return totalResources() == 0; }
    }

    private final BrokerReceiverRuntime dynamic;
    private final BrokerManifestReceiverRuntime manifest;
    private final BrokerOrderedReceiverRuntime ordered;

    public ReceiverLifecycleCoordinator(BrokerReceiverRuntime dynamic,
                                        BrokerManifestReceiverRuntime manifest,
                                        BrokerOrderedReceiverRuntime ordered) {
        if (dynamic == null || manifest == null || ordered == null) {
            throw new IllegalArgumentException("Receiver lifecycle registries are required");
        }
        this.dynamic = dynamic;
        this.manifest = manifest;
        this.ordered = ordered;
    }

    public synchronized int bindSession(GuestSession session) {
        requireBindableSession(session);
        return manifest.bindSession(session);
    }

    /** Process loss removes Session-owned state but preserves Manifest package declarations. */
    public synchronized CleanupResult disconnectSession(GuestSession session, String reason) {
        return cleanupSession(session, reason);
    }

    /** Explicit stop and failure use the same Session/generation cleanup semantics. */
    public synchronized CleanupResult stopSession(GuestSession session, String reason) {
        return cleanupSession(session, reason);
    }

    /** Dynamic registrations are not resurrected; only the current process binding is rebound. */
    public synchronized RecoveryResult recoverSession(GuestSession stale, GuestSession current) {
        requireSession(stale);
        requireBindableSession(current);
        if (!stale.packageName().equals(current.packageName())
                || stale.virtualUserId() != current.virtualUserId()
                || !stale.processName().equals(current.processName())
                || current.generation() <= stale.generation()) {
            throw new SecurityException("RECEIVER_RECOVERY_IDENTITY_MISMATCH");
        }
        CleanupResult cleanup = cleanupSession(stale, "ORDERED_RECEIVER_STALE_GENERATION");
        int rebound = manifest.bindSession(current);
        return new RecoveryResult(cleanup, rebound);
    }

    /** Removes package index, startup template and every Session-owned resource for an App instance. */
    public synchronized CleanupResult invalidateInstance(String packageName, int virtualUserId,
                                                         String reason) {
        requireInstance(packageName, virtualUserId);
        int dynamicCount = dynamic.removeInstance(packageName, virtualUserId);
        BrokerManifestReceiverRuntime.Snapshot removed = manifest.removeInstance(packageName, virtualUserId);
        int orderedCount = ordered.cancelInstance(packageName, virtualUserId, reason);
        return cleanup(dynamicCount, removed.bindings(), orderedCount, removed);
    }

    public synchronized int purgeExpired() { return ordered.purgeExpired(); }

    public synchronized CleanupResult invalidateAll(String reason) {
        int dynamicCount = dynamic.clear();
        BrokerManifestReceiverRuntime.Snapshot removed = manifest.clear();
        int orderedCount = ordered.cancelAll(reason);
        return cleanup(dynamicCount, removed.bindings(), orderedCount, removed);
    }

    public synchronized Snapshot snapshot() {
        DynamicReceiverRegistry.Snapshot dynamicSnapshot = dynamic.snapshot();
        BrokerManifestReceiverRuntime.Snapshot manifestSnapshot = manifest.snapshot();
        return new Snapshot(dynamicSnapshot.registrations(), dynamicSnapshot.actionSubscriptions(),
                manifestSnapshot.packages(), manifestSnapshot.receivers(), manifestSnapshot.bindings(),
                manifestSnapshot.actionIndexKeys(), manifestSnapshot.actionIndexEntries(),
                manifestSnapshot.startupTemplates(), ordered.pendingCount());
    }

    private CleanupResult cleanupSession(GuestSession session, String reason) {
        requireSession(session);
        return new CleanupResult(dynamic.removeSession(session),
                manifest.removeSessionCount(session),
                ordered.cancelSession(session, reason),
                0, 0, 0, 0, 0);
    }

    private static CleanupResult cleanup(int dynamicCount, int bindingCount, int orderedCount,
                                         BrokerManifestReceiverRuntime.Snapshot removed) {
        return new CleanupResult(dynamicCount, bindingCount, orderedCount,
                removed.packages(), removed.receivers(), removed.actionIndexKeys(),
                removed.actionIndexEntries(), removed.startupTemplates());
    }

    private static void requireSession(GuestSession session) {
        if (session == null) throw new IllegalArgumentException("session is required");
    }

    private static void requireBindableSession(GuestSession session) {
        requireSession(session);
        SessionState state = session.state();
        if (state != SessionState.PREPARING && state != SessionState.READY
                && state != SessionState.ACTIVE) {
            throw new IllegalStateException("RECEIVER_SESSION_NOT_BINDABLE:" + state);
        }
    }

    private static void requireInstance(String packageName, int virtualUserId) {
        if (packageName == null || !packageName.trim().matches(
                "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("packageName is invalid");
        }
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
    }

    private static int checkedTotal(int... values) {
        long total = 0;
        for (int value : values) total += value;
        if (total > Integer.MAX_VALUE) {
            throw new IllegalStateException("RECEIVER_RESOURCE_COUNT_OVERFLOW");
        }
        return (int) total;
    }
}
