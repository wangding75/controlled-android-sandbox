package com.warden.controlledsandbox.framework.activity;

import com.warden.controlledsandbox.framework.routing.OneTimeRouteStore;
import com.warden.controlledsandbox.framework.routing.RouteOwner;
import com.warden.controlledsandbox.framework.routing.RoutePayload;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

public final class ActivityLaunchCoordinatorSelfTest {
    private ActivityLaunchCoordinatorSelfTest() {}

    public static void main(String[] args) {
        testCreatedAndReusedLaunchesHaveIndependentPayloads();
        testLedgerFailureRollsBackRouteToken();
        testOwnerMismatchDoesNotBurnTransaction();
        testPollNewIntentConsumesOnePayload();
        testPollNewIntentRejectsWrongOwnerBeforeQueueMutation();
        testProcessRecreationRevokesStaleRoutes();
        testInvalidProcessRecreationDoesNotRevokeRoutes();
        testProcessInvalidationRemovesRoutesAndActivities();
        System.out.println("PASS ActivityLaunchCoordinatorSelfTest");
    }

    private static void testCreatedAndReusedLaunchesHaveIndependentPayloads() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        OneTimeRouteStore store = routeStore();
        ActivityLaunchCoordinator coordinator = new ActivityLaunchCoordinator(ledger, store);

        ActivityLaunchTransaction first = coordinator.launch(
                spec(LaunchMode.STANDARD, 0, null),
                new byte[] {1},
                Map.of("intent", "initial"),
                Duration.ofSeconds(30));
        ActivityLaunchTransaction second = coordinator.launch(
                spec(LaunchMode.SINGLE_TOP, LaunchFlags.SINGLE_TOP, first.decision().taskId()),
                new byte[] {2},
                Map.of("intent", "new"),
                Duration.ofSeconds(30));

        check(first.decision().action() == LaunchAction.CREATED_TASK,
                "first launch should create a task");
        check(second.decision().action() == LaunchAction.DELIVERED_NEW_INTENT,
                "second launch should reuse the top Activity");
        check(!first.routeToken().value().equals(second.routeToken().value()),
                "each launch transaction needs a distinct token");
        RoutePayload firstPayload = coordinator.consumePayload(
                first, first.routeOwner()).orElseThrow();
        RoutePayload secondPayload = coordinator.consumePayload(
                second, second.routeOwner()).orElseThrow();
        check(firstPayload.bytes()[0] == 1, "initial payload should remain distinct");
        check(secondPayload.bytes()[0] == 2, "new-Intent payload should remain distinct");
        check(firstPayload.metadata().get("intent").equals("initial"),
                "initial metadata should remain distinct");
        check(secondPayload.metadata().get("intent").equals("new"),
                "new-Intent metadata should remain distinct");
        check(store.size() == 0, "both transactions should be consumed exactly once");
    }

    private static void testLedgerFailureRollsBackRouteToken() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        OneTimeRouteStore store = routeStore();
        ActivityLaunchCoordinator coordinator = new ActivityLaunchCoordinator(ledger, store);
        try {
            coordinator.launch(
                    spec(LaunchMode.STANDARD, LaunchFlags.CLEAR_TASK, null),
                    new byte[] {3},
                    Map.of(),
                    Duration.ofSeconds(30));
            throw new AssertionError("invalid launch should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("CLEAR_TASK"),
                    "ledger validation failure should remain visible");
        }
        check(store.size() == 0, "failed launch must revoke its preallocated route token");
        check(ledger.activityCount() == 0, "failed launch must not mutate the Activity ledger");
    }

    private static void testOwnerMismatchDoesNotBurnTransaction() {
        ActivityLaunchCoordinator coordinator = new ActivityLaunchCoordinator(
                new ActivityTaskLedger(), routeStore());
        ActivityLaunchTransaction transaction = coordinator.launch(
                spec(LaunchMode.STANDARD, 0, null),
                new byte[] {4},
                Map.of(),
                Duration.ofSeconds(30));
        RouteOwner wrongOwner = new RouteOwner(
                1, "guest.example", "guest.example:main", 1);
        try {
            coordinator.consumePayload(transaction, wrongOwner);
            throw new AssertionError("wrong virtual user should fail");
        } catch (SecurityException expected) {
            check(expected.getMessage().contains("owner mismatch"),
                    "owner mismatch should be explicit");
        }
        check(coordinator.consumePayload(transaction, transaction.routeOwner()).isPresent(),
                "rejected consume must not burn the valid transaction");
    }

    private static void testPollNewIntentConsumesOnePayload() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        OneTimeRouteStore store = routeStore();
        ActivityLaunchCoordinator coordinator = new ActivityLaunchCoordinator(ledger, store);
        ActivityLaunchTransaction first = coordinator.launch(
                spec(LaunchMode.STANDARD, 0, null),
                new byte[] {5},
                Map.of(),
                Duration.ofSeconds(30));
        ActivityLaunchTransaction second = coordinator.launch(
                spec(LaunchMode.SINGLE_TOP, LaunchFlags.SINGLE_TOP, first.decision().taskId()),
                new byte[] {6},
                Map.of(),
                Duration.ofSeconds(30));

        RoutedNewIntent routed = coordinator.pollNewIntent(
                first.decision().activityToken(), second.routeOwner()).orElseThrow();
        check(routed.delivery().routeToken().equals(second.routeToken().value()),
                "new-Intent delivery should reference the second launch token");
        check(routed.payload().bytes()[0] == 6,
                "new-Intent delivery should consume the second launch payload");
        check(coordinator.pollNewIntent(
                first.decision().activityToken(), second.routeOwner()).isEmpty(),
                "poll must consume only the available queue entry");
        check(coordinator.consumePayload(first, first.routeOwner()).isPresent(),
                "initial launch payload should remain independent");
    }

    private static void testPollNewIntentRejectsWrongOwnerBeforeQueueMutation() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        ActivityLaunchCoordinator coordinator = new ActivityLaunchCoordinator(ledger, routeStore());
        ActivityLaunchTransaction first = coordinator.launch(
                spec(LaunchMode.STANDARD, 0, null),
                new byte[] {7},
                Map.of(),
                Duration.ofSeconds(30));
        ActivityLaunchTransaction second = coordinator.launch(
                spec(LaunchMode.SINGLE_TOP, LaunchFlags.SINGLE_TOP, first.decision().taskId()),
                new byte[] {8},
                Map.of(),
                Duration.ofSeconds(30));
        RouteOwner wrongOwner = new RouteOwner(
                0, "guest.example", "guest.example:main", 2);
        try {
            coordinator.pollNewIntent(first.decision().activityToken(), wrongOwner);
            throw new AssertionError("wrong process generation should fail");
        } catch (SecurityException expected) {
            check(expected.getMessage().contains("owner mismatch"),
                    "Activity owner mismatch should be explicit");
        }
        check(coordinator.pollNewIntent(
                first.decision().activityToken(), second.routeOwner()).isPresent(),
                "owner rejection must not remove the queued new Intent");
    }

    private static void testProcessRecreationRevokesStaleRoutes() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        OneTimeRouteStore store = routeStore();
        ActivityLaunchCoordinator coordinator = new ActivityLaunchCoordinator(ledger, store);
        ActivityLaunchTransaction first = coordinator.launch(
                spec(LaunchMode.STANDARD, 0, null, 3),
                new byte[] {9},
                Map.of(),
                Duration.ofSeconds(30));
        coordinator.launch(
                spec(
                        LaunchMode.SINGLE_TOP,
                        LaunchFlags.SINGLE_TOP,
                        first.decision().taskId(),
                        3),
                new byte[] {10},
                Map.of(),
                Duration.ofSeconds(30));
        check(store.size() == 2, "both stale-generation routes should exist before restart");

        ProcessRecreationOutcome outcome = coordinator.recreateProcessGeneration(
                0, "guest.example", "guest.example:main", 3, 4);
        check(outcome.recreations().size() == 1, "one live Activity should recreate");
        check(outcome.revokedRouteCount() == 2, "all stale-generation routes should be revoked");
        check(store.size() == 0, "no stale route may survive process recreation");
        String currentToken = outcome.recreations().get(0).currentActivityToken();
        check(ledger.processIdentity(currentToken).processGeneration() == 4,
                "recreated Activity should use the new process generation");
        check(coordinator.pollNewIntent(
                currentToken,
                new RouteOwner(0, "guest.example", "guest.example:main", 4)).isEmpty(),
                "stale new-Intent queue should be empty after process recreation");
    }

    private static void testInvalidProcessRecreationDoesNotRevokeRoutes() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        OneTimeRouteStore store = routeStore();
        ActivityLaunchCoordinator coordinator = new ActivityLaunchCoordinator(ledger, store);
        coordinator.launch(
                spec(LaunchMode.STANDARD, 0, null, 6),
                new byte[] {12},
                Map.of(),
                Duration.ofSeconds(30));
        try {
            coordinator.recreateProcessGeneration(
                    0, "guest.example", "guest.example:main", 6, 6);
            throw new AssertionError("non-increasing generation should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("greater"),
                    "generation validation should be explicit");
        }
        check(store.size() == 1, "invalid transition must not revoke valid routes");
        check(ledger.activityCount() == 1, "invalid transition must not mutate the ledger");
    }

    private static void testProcessInvalidationRemovesRoutesAndActivities() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        OneTimeRouteStore store = routeStore();
        ActivityLaunchCoordinator coordinator = new ActivityLaunchCoordinator(ledger, store);
        coordinator.launch(
                spec(LaunchMode.STANDARD, 0, null, 5),
                new byte[] {11},
                Map.of(),
                Duration.ofSeconds(30));
        ProcessInvalidationOutcome outcome = coordinator.invalidateProcessGeneration(
                0, "guest.example", "guest.example:main", 5);
        check(outcome.removedActivityCount() == 1,
                "hard invalidation should remove the live Activity");
        check(outcome.revokedRouteCount() == 1,
                "hard invalidation should revoke the pending route");
        check(ledger.activityCount() == 0, "hard invalidation should clear the ledger");
        check(store.size() == 0, "hard invalidation should clear stale route storage");
    }

    private static ActivityLaunchSpec spec(
            LaunchMode mode,
            int flags,
            Integer callerTaskId) {
        return spec(mode, flags, callerTaskId, 1);
    }

    private static ActivityLaunchSpec spec(
            LaunchMode mode,
            int flags,
            Integer callerTaskId,
            long processGeneration) {
        return new ActivityLaunchSpec(
                new ActivityIdentity(0, "guest.example", "guest.example/.MainActivity"),
                "guest.example",
                mode,
                flags,
                callerTaskId,
                "guest.example:main",
                processGeneration,
                "",
                -1);
    }

    private static OneTimeRouteStore routeStore() {
        return new OneTimeRouteStore(
                Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC),
                16,
                1024,
                Duration.ofMinutes(1));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
