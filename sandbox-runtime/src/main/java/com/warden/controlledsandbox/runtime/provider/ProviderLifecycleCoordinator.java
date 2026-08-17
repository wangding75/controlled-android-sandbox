package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single lifecycle authority for Broker-owned Provider resources.
 *
 * <p>The individual registries remain responsible for their own synchronization. This coordinator serializes
 * cross-registry invalidation so Session, generation and instance transitions cannot leave only one Provider
 * capability family alive.</p>
 */
public final class ProviderLifecycleCoordinator {
    public static final class CleanupResult {
        private final int authorities;
        private final int observers;
        private final int grants;
        private final List<BrokerCursorRuntime.Lease> cursors;
        private final List<BrokerFileRuntime.Lease> files;

        private CleanupResult(int authorities, int observers, int grants,
                              List<BrokerCursorRuntime.Lease> cursors,
                              List<BrokerFileRuntime.Lease> files) {
            this.authorities = authorities;
            this.observers = observers;
            this.grants = grants;
            this.cursors = immutable(cursors);
            this.files = immutable(files);
        }

        int authorities() { return authorities; }
        int observers() { return observers; }
        int grants() { return grants; }
        public List<BrokerCursorRuntime.Lease> cursors() { return cursors; }
        public List<BrokerFileRuntime.Lease> files() { return files; }
        int cursorsRemoved() { return cursors.size(); }
        int filesRemoved() { return files.size(); }
        int totalRemoved() { return authorities + observers + grants + cursors.size() + files.size(); }

        CleanupResult plus(CleanupResult other) {
            if (other == null) return this;
            List<BrokerCursorRuntime.Lease> mergedCursors = new ArrayList<>(cursors);
            mergedCursors.addAll(other.cursors);
            List<BrokerFileRuntime.Lease> mergedFiles = new ArrayList<>(files);
            mergedFiles.addAll(other.files);
            return new CleanupResult(authorities + other.authorities,
                    observers + other.observers, grants + other.grants,
                    mergedCursors, mergedFiles);
        }
    }

    public static final class RecoveryResult {
        private final int authoritiesRebound;
        private final int grantsRebound;
        private final CleanupResult staleResources;

        private RecoveryResult(int authoritiesRebound, int grantsRebound,
                               CleanupResult staleResources) {
            this.authoritiesRebound = authoritiesRebound;
            this.grantsRebound = grantsRebound;
            this.staleResources = staleResources;
        }

        int authoritiesRebound() { return authoritiesRebound; }
        public int grantsRebound() { return grantsRebound; }
        public CleanupResult staleResources() { return staleResources; }
    }

    public static final class Snapshot {
        private final int authorities;
        private final int observers;
        private final int grants;
        private final int cursors;
        private final int files;

        private Snapshot(int authorities, int observers, int grants, int cursors, int files) {
            this.authorities = authorities;
            this.observers = observers;
            this.grants = grants;
            this.cursors = cursors;
            this.files = files;
        }

        public int authorities() { return authorities; }
        public int observers() { return observers; }
        public int grants() { return grants; }
        public int cursors() { return cursors; }
        public int files() { return files; }
        public int total() { return authorities + observers + grants + cursors + files; }
        boolean empty() { return total() == 0; }
    }

    private final BrokerProviderRuntime providers;
    private final BrokerCursorRuntime cursors;
    private final BrokerFileRuntime files;
    private final BrokerObserverRuntime observers;
    private final BrokerProviderQueryCancellation queryCancellations;
    private final UriGrantRegistry grants;

    public ProviderLifecycleCoordinator(BrokerProviderRuntime providers,
                                 BrokerCursorRuntime cursors,
                                 BrokerFileRuntime files,
                                 BrokerObserverRuntime observers,
                                 UriGrantRegistry grants) {
        this(providers, cursors, files, observers, new BrokerProviderQueryCancellation(), grants);
    }

    public ProviderLifecycleCoordinator(BrokerProviderRuntime providers,
                                 BrokerCursorRuntime cursors,
                                 BrokerFileRuntime files,
                                 BrokerObserverRuntime observers,
                                 BrokerProviderQueryCancellation queryCancellations,
                                 UriGrantRegistry grants) {
        if (providers == null || cursors == null || files == null || observers == null || grants == null) {
            throw new IllegalArgumentException("Provider lifecycle registries are required");
        }
        this.providers = providers;
        this.cursors = cursors;
        this.files = files;
        this.observers = observers;
        this.queryCancellations = queryCancellations == null
                ? new BrokerProviderQueryCancellation() : queryCancellations;
        this.grants = grants;
    }

    /**
     * Preserve recoverable authority and URI permissions; revoke process-bound capabilities.
     * Terminal disconnects remove all ownership.
     */
    public synchronized CleanupResult disconnectSession(GuestSession session) {
        requireSession(session);
        int authorityCount = session.state() == SessionState.RECOVERING ? 0 : providers.invalidate(session);
        return cleanupSessionCapabilities(session, authorityCount, session.state() != SessionState.RECOVERING);
    }

    /** Remove authority ownership and every capability tied to an explicitly stopped or failed Session. */
    public synchronized CleanupResult stopSession(GuestSession session) {
        requireSession(session);
        return cleanupSessionCapabilities(session, providers.invalidate(session), true);
    }

    /** Rebind authority ownership and revoke every capability issued under the stale generation. */
    public synchronized RecoveryResult recoverSession(GuestSession stale, GuestSession current) {
        requireSession(stale);
        requireSession(current);
        int rebound = providers.processRecovered(stale, current);
        int grantsRebound = grants.rebindSession(stale.sessionId(), stale.generation(),
                current.sessionId(), current.generation());
        return new RecoveryResult(rebound, grantsRebound,
                cleanupSessionCapabilities(stale, 0, false));
    }

    /** Remove all Provider resources owned by or issued to one virtual App instance. */
    public synchronized CleanupResult invalidateInstance(String packageName, int virtualUserId) {
        String instance = BrokerProviderRuntime.instanceId(packageName, virtualUserId);
        int authorityCount = providers.invalidateInstance(packageName, virtualUserId);
        int observerCount = observers.invalidateInstance(instance);
        int grantCount = grants.revokeInstance(instance);
        queryCancellations.invalidateInstance(instance);
        return new CleanupResult(authorityCount, observerCount, grantCount,
                cursors.invalidateInstance(instance), files.invalidateInstance(instance));
    }

    /** Purge all time-bounded Provider capabilities using one Broker timestamp. */
    public synchronized CleanupResult purgeExpired(long nowMs) {
        int expiredGrants = grants.purgeExpiredGrants(nowMs);
        queryCancellations.purgeExpired();
        return new CleanupResult(0, 0, expiredGrants,
                cursors.purgeExpired(nowMs), files.purgeExpired(nowMs));
    }

    public synchronized Snapshot snapshot(long nowMs) {
        return new Snapshot(providers.size(), observers.size(), grants.size(nowMs),
                cursors.size(nowMs), files.size());
    }

    private CleanupResult cleanupSessionCapabilities(GuestSession session, int authorityCount,
                                                     boolean revokeGrants) {
        int observerCount = observers.invalidateSession(session.sessionId(), session.generation());
        int grantCount = revokeGrants
                ? grants.revokeSession(session.sessionId(), session.generation()) : 0;
        queryCancellations.invalidateSession(session.sessionId(), session.generation());
        return new CleanupResult(authorityCount, observerCount, grantCount,
                cursors.invalidateSession(session.sessionId(), session.generation()),
                files.invalidateSession(session.sessionId(), session.generation()));
    }

    private static void requireSession(GuestSession session) {
        if (session == null) throw new IllegalArgumentException("session is required");
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
