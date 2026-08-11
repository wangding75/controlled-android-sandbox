package com.warden.controlledsandbox.framework.activity;


import com.warden.controlledsandbox.framework.routing.OneTimeRouteStore;
import com.warden.controlledsandbox.framework.routing.RouteKind;
import com.warden.controlledsandbox.framework.routing.RouteOwner;
import com.warden.controlledsandbox.framework.routing.RoutePayload;
import com.warden.controlledsandbox.framework.routing.RouteToken;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Atomic host-side entry point for one virtual Activity launch transaction.
 *
 * <p>The payload is stored before task mutation so the returned decision never references a missing
 * token. If task mutation fails, the newly issued token is revoked before the exception escapes.</p>
 */
public final class ActivityLaunchCoordinator {
    private final ActivityTaskLedger ledger;
    private final OneTimeRouteStore routeStore;

    public ActivityLaunchCoordinator(
            ActivityTaskLedger ledger,
            OneTimeRouteStore routeStore) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.routeStore = Objects.requireNonNull(routeStore, "routeStore");
    }

    public synchronized ActivityLaunchTransaction launch(
            ActivityLaunchSpec spec,
            byte[] payload,
            Map<String, String> metadata,
            Duration ttl) {
        Objects.requireNonNull(spec, "spec");
        RouteOwner owner = spec.routeOwner();
        RouteToken routeToken = routeStore.put(
                owner,
                RouteKind.ACTIVITY_INTENT,
                payload,
                metadata,
                ttl);
        try {
            LaunchDecision decision = ledger.launch(spec.toLaunchRequest(routeToken.value()));
            return new ActivityLaunchTransaction(decision, routeToken, owner);
        } catch (RuntimeException | Error failure) {
            routeStore.revoke(routeToken.value());
            throw failure;
        }
    }


    /**
     * Delivers at most one queued new Intent. Expired or externally revoked route tokens are
     * discarded fail-closed and the next queued delivery is examined.
     */
    public synchronized Optional<RoutedNewIntent> pollNewIntent(
            String activityToken,
            RouteOwner expectedOwner) {
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        ActivityProcessIdentity identity = ledger.processIdentity(activityToken);
        RouteOwner actualOwner = new RouteOwner(
                identity.virtualUserId(),
                identity.packageName(),
                identity.processName(),
                identity.processGeneration());
        if (!actualOwner.equals(expectedOwner)) {
            throw new SecurityException("Activity process owner mismatch");
        }
        while (true) {
            Optional<NewIntentDelivery> delivery = ledger.pollNewIntent(activityToken);
            if (delivery.isEmpty()) {
                return Optional.empty();
            }
            Optional<RoutePayload> payload = routeStore.consume(
                    delivery.get().routeToken(),
                    expectedOwner,
                    RouteKind.ACTIVITY_INTENT);
            if (payload.isPresent()) {
                return Optional.of(new RoutedNewIntent(delivery.get(), payload.get()));
            }
        }
    }

    public synchronized ProcessRecreationOutcome recreateProcessGeneration(
            int virtualUserId,
            String packageName,
            String processName,
            long staleGeneration,
            long newGeneration) {
        RouteOwner staleOwner = new RouteOwner(
                virtualUserId,
                packageName,
                processName,
                staleGeneration);
        if (newGeneration <= staleGeneration) {
            throw new IllegalArgumentException(
                    "new generation must be greater than stale generation");
        }
        // ActivityTaskManager can recreate a Host trampoline after a Guest process restart.
        // Rebind the still-unconsumed route to the new virtual generation instead of revoking
        // an accepted platform launch.
        routeStore.rebindStaleGenerations(
                staleOwner.virtualUserId(),
                staleOwner.packageName(),
                staleOwner.processName(),
                staleOwner.processGeneration(),
                newGeneration);
        return new ProcessRecreationOutcome(
                ledger.recreateProcessGeneration(
                        staleOwner.virtualUserId(),
                        staleOwner.packageName(),
                        staleOwner.processName(),
                        staleOwner.processGeneration(),
                        newGeneration),
                0);
    }

    public synchronized ProcessInvalidationOutcome invalidateProcessGeneration(
            int virtualUserId,
            String packageName,
            String processName,
            long staleGeneration) {
        RouteOwner staleOwner = new RouteOwner(
                virtualUserId,
                packageName,
                processName,
                staleGeneration);
        int revoked = routeStore.revokeStaleGenerations(
                staleOwner.virtualUserId(),
                staleOwner.packageName(),
                staleOwner.processName(),
                staleOwner.processGeneration());
        int removed = ledger.invalidateProcessGeneration(
                staleOwner.virtualUserId(),
                staleOwner.packageName(),
                staleOwner.processName(),
                staleOwner.processGeneration());
        return new ProcessInvalidationOutcome(removed, revoked);
    }

    public synchronized Optional<RoutePayload> consumePayload(
            ActivityLaunchTransaction transaction,
            RouteOwner expectedOwner) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        return routeStore.consume(
                transaction.routeToken().value(),
                expectedOwner,
                RouteKind.ACTIVITY_INTENT);
    }
}
