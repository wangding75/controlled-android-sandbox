package com.warden.controlledsandbox.framework.activity;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ActivityTaskLedgerSelfTest {
    private ActivityTaskLedgerSelfTest() {
    }

    public static void main(String[] args) {
        testStandardAndSingleTop();
        testSingleTaskClearTop();
        testReorderAndClearTask();
        testSingleInstanceExclusivityAndReuse();
        testSingleInstancePerTask();
        testVirtualUserIsolation();
        testLifecycleAndGenerationInvalidation();
        testActivityResultRoundTrip();
        testClearTopCancelsPendingResult();
        testNewIntentQueue();
        testSavedStateAndConfigurationRecreation();
        testProcessGenerationRecreationRewritesResultCaller();
        testProcessRestartDropsStaleTransportTokens();
        testConfigurationRecreationPreservesTransportTokens();
        testSavedStateBoundsAndVersionCollision();
        testFiveHundredLaunchesWithoutLedgerLeak();
        System.out.println("PASS ActivityTaskLedgerSelfTest");
    }

    private static void testStandardAndSingleTop() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision first = ledger.launch(requestWithRoute(
                0, "A", LaunchMode.STANDARD, 0, null, 1, "route-initial"));
        LaunchDecision second = ledger.launch(requestWithRoute(
                0, "A", LaunchMode.SINGLE_TOP, 0, first.taskId(), 1, "route-new-intent"));
        check(second.action() == LaunchAction.DELIVERED_NEW_INTENT, "singleTop should reuse top");
        check(second.activityToken().equals(first.activityToken()), "singleTop token should be stable");
        check(first.routeToken().equals("route-initial"), "created launch must expose its route token");
        check(second.routeToken().equals("route-new-intent"),
                "reused launch must expose the current route token");
        check(ledger.activityCount() == 1, "singleTop must not create duplicate Activity");
    }

    private static void testSingleTaskClearTop() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision a = ledger.launch(request(0, "A", LaunchMode.STANDARD, 0, null, 1));
        ledger.launch(request(0, "B", LaunchMode.STANDARD, 0, a.taskId(), 1));
        ledger.launch(request(0, "C", LaunchMode.STANDARD, 0, a.taskId(), 1));
        LaunchDecision reused = ledger.launch(request(0, "A", LaunchMode.SINGLE_TASK, 0, null, 1));
        check(reused.action() == LaunchAction.CLEARED_TOP, "singleTask should clear above existing");
        check(reused.removedActivityCount() == 2, "two Activities should be removed");
        check(ledger.activityCount() == 1, "only target Activity should remain");
    }

    private static void testReorderAndClearTask() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision a = ledger.launch(request(0, "A", LaunchMode.STANDARD, LaunchFlags.NEW_TASK, null, 1));
        ledger.launch(request(0, "B", LaunchMode.STANDARD, 0, a.taskId(), 1));
        ledger.launch(request(0, "C", LaunchMode.STANDARD, 0, a.taskId(), 1));
        LaunchDecision reordered = ledger.launch(request(
                0, "B", LaunchMode.STANDARD, LaunchFlags.REORDER_TO_FRONT, a.taskId(), 1));
        check(reordered.action() == LaunchAction.REORDERED_TO_FRONT, "B should move to front");
        List<ActivitySnapshot> stack = ledger.snapshot().get(0).activities();
        check(stack.get(stack.size() - 1).identity().componentName().equals("B"), "B must be top");

        LaunchDecision reset = ledger.launch(request(
                0,
                "D",
                LaunchMode.STANDARD,
                LaunchFlags.NEW_TASK | LaunchFlags.CLEAR_TASK,
                null,
                1));
        check(!reset.createdNewTask(), "CLEAR_TASK should reuse the selected task identity");
        check(ledger.activityCount() == 1, "CLEAR_TASK should remove prior stack");
    }

    private static void testSingleInstanceExclusivityAndReuse() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision first = ledger.launch(request(
                0, "Solo", LaunchMode.SINGLE_INSTANCE, LaunchFlags.NEW_TASK, null, 1));
        LaunchDecision second = ledger.launch(request(
                0, "Solo", LaunchMode.SINGLE_INSTANCE, LaunchFlags.NEW_TASK, null, 1));
        check(second.action() == LaunchAction.DELIVERED_NEW_INTENT,
                "singleInstance should globally reuse its existing Activity");
        check(second.taskId() == first.taskId(), "singleInstance task should be stable");
        check(second.activityToken().equals(first.activityToken()),
                "singleInstance token should be stable");

        LaunchDecision child = ledger.launch(request(
                0, "Child", LaunchMode.STANDARD, 0, first.taskId(), 1));
        check(child.taskId() != first.taskId(),
                "another Activity must not enter a singleInstance task");
        check(ledger.taskCount() == 2, "exclusive launch should create a second task");

        List<TaskSnapshot> tasks = ledger.snapshot();
        check(tasks.get(tasks.size() - 1).taskId() == child.taskId(),
                "snapshot should preserve front-task order");
    }

    private static void testSingleInstancePerTask() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision first = ledger.launch(request(
                0, "PerTask", LaunchMode.SINGLE_INSTANCE_PER_TASK,
                LaunchFlags.NEW_TASK, null, 1));
        LaunchDecision reused = ledger.launch(request(
                0, "PerTask", LaunchMode.SINGLE_INSTANCE_PER_TASK,
                LaunchFlags.NEW_TASK, null, 1));
        check(reused.action() == LaunchAction.DELIVERED_NEW_INTENT,
                "singleInstancePerTask should reuse within the selected task");
        check(reused.activityToken().equals(first.activityToken()),
                "singleInstancePerTask token should be stable within task");

        LaunchDecision separate = ledger.launch(request(
                0, "PerTask", LaunchMode.SINGLE_INSTANCE_PER_TASK,
                LaunchFlags.NEW_TASK | LaunchFlags.MULTIPLE_TASK, null, 1));
        check(separate.taskId() != first.taskId(),
                "MULTIPLE_TASK should permit a separate per-task instance");
        check(ledger.activityCount() == 2, "two per-task instances expected");
    }

    private static void testVirtualUserIsolation() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision user0 = ledger.launch(request(0, "A", LaunchMode.SINGLE_TASK, LaunchFlags.NEW_TASK, null, 1));
        LaunchDecision user1 = ledger.launch(request(1, "A", LaunchMode.SINGLE_TASK, LaunchFlags.NEW_TASK, null, 1));
        check(user0.taskId() != user1.taskId(), "virtual users must not share tasks");
        check(ledger.taskCount() == 2, "two isolated user tasks expected");
        ledger.clearVirtualUser(0);
        check(ledger.taskCount() == 1, "clearing one user must preserve the other");
        check(ledger.snapshot().get(0).virtualUserId() == 1, "remaining task belongs to user 1");
    }

    private static void testLifecycleAndGenerationInvalidation() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision launch = ledger.launch(request(0, "A", LaunchMode.STANDARD, 0, null, 3));
        check(ledger.transition(launch.activityToken(), LifecycleState.CREATED), "create transition expected");
        check(ledger.transition(launch.activityToken(), LifecycleState.STARTED), "start transition expected");
        check(ledger.transition(launch.activityToken(), LifecycleState.RESUMED), "resume transition expected");
        try {
            ledger.transition(launch.activityToken(), LifecycleState.CREATED);
            throw new AssertionError("invalid transition should fail");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("RESUMED"), "transition failure should be explicit");
        }
        int removed = ledger.invalidateProcessGeneration(0, "guest.example", "guest.example:main", 3);
        check(removed == 1, "stale generation should be invalidated");
        check(ledger.activityCount() == 0, "invalidated Activity should be removed");
    }

    private static void testActivityResultRoundTrip() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision caller = ledger.launch(request(0, "Caller", LaunchMode.STANDARD, 0, null, 1));
        LaunchDecision callee = ledger.launch(requestForResult(
                0,
                "Callee",
                LaunchMode.STANDARD,
                0,
                caller.taskId(),
                1,
                "fragment:main",
                42));
        check(ledger.finishWithResult(callee.activityToken(), -1, "route-result-1"),
                "callee should finish with a result");
        List<ActivityResultDelivery> results = ledger.drainActivityResults(caller.activityToken());
        check(results.size() == 1, "one result should be delivered");
        ActivityResultDelivery result = results.get(0);
        check(result.callerActivityToken().equals(caller.activityToken()),
                "result caller token should match");
        check(result.calleeActivityToken().equals(callee.activityToken()),
                "result callee token should match");
        check(result.requestCode() == 42, "request code should be preserved");
        check(result.resultCode() == -1, "result code should be preserved");
        check(result.dataToken().equals("route-result-1"), "result data token should be preserved");
    }

    private static void testClearTopCancelsPendingResult() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision caller = ledger.launch(request(0, "A", LaunchMode.STANDARD, 0, null, 1));
        ledger.launch(requestForResult(
                0,
                "B",
                LaunchMode.STANDARD,
                0,
                caller.taskId(),
                1,
                "",
                7));
        ledger.launch(request(0, "C", LaunchMode.STANDARD, 0, caller.taskId(), 1));
        LaunchDecision clear = ledger.launch(request(0, "A", LaunchMode.SINGLE_TASK, 0, null, 1));
        check(clear.removedActivityCount() == 2, "B and C should be removed");
        List<ActivityResultDelivery> results = ledger.drainActivityResults(caller.activityToken());
        check(results.size() == 1, "removed callee should emit one canceled result");
        check(results.get(0).resultCode() == ActivityTaskLedger.RESULT_CANCELED,
                "cleared callee result must be canceled");
        check(results.get(0).requestCode() == 7, "canceled request code should be preserved");
    }

    private static void testNewIntentQueue() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision first = ledger.launch(requestWithRoute(
                0, "A", LaunchMode.STANDARD, 0, null, 1, "route-launch"));
        ledger.launch(requestWithRoute(
                0,
                "A",
                LaunchMode.SINGLE_TOP,
                LaunchFlags.SINGLE_TOP,
                first.taskId(),
                1,
                "route-new-intent"));
        List<NewIntentDelivery> deliveries = ledger.drainNewIntents(first.activityToken());
        check(deliveries.size() == 1, "singleTop should enqueue one new Intent");
        check(deliveries.get(0).activityToken().equals(first.activityToken()),
                "new Intent target should be stable");
        check(deliveries.get(0).routeToken().equals("route-new-intent"),
                "new Intent must retain its one-time route token");
        check(deliveries.get(0).sourceTaskId().equals(first.taskId()),
                "new Intent source task should be preserved");
        check(ledger.drainNewIntents(first.activityToken()).isEmpty(),
                "new Intent delivery must drain exactly once");
    }

    private static void testSavedStateAndConfigurationRecreation() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision launch = ledger.launch(request(0, "A", LaunchMode.STANDARD, 0, null, 3));
        Map<String, String> source = new LinkedHashMap<>();
        source.put("route", "state-route-1");
        SavedActivityState state = new SavedActivityState(1, source);
        check(ledger.saveInstanceState(launch.activityToken(), state), "first state save should change state");
        source.put("mutated", "outside");
        check(!ledger.savedInstanceState(launch.activityToken()).orElseThrow().values().containsKey("mutated"),
                "saved state must be defensively copied");

        ConfigurationDecision delivered = ledger.handleConfigurationChange(
                launch.activityToken(), "config-land", true);
        check(delivered.action() == ConfigurationAction.DELIVERED_TO_EXISTING,
                "handled configuration should keep the instance");
        check(delivered.currentActivityToken().equals(launch.activityToken()),
                "handled configuration should preserve token");

        ConfigurationDecision recreated = ledger.handleConfigurationChange(
                launch.activityToken(), "config-port", false);
        check(recreated.action() == ConfigurationAction.RECREATED,
                "unhandled configuration should recreate");
        check(!recreated.currentActivityToken().equals(launch.activityToken()),
                "recreation must rotate token");
        check(ledger.savedInstanceState(recreated.currentActivityToken()).orElseThrow().equals(state),
                "saved state must survive configuration recreation");
        ActivitySnapshot snapshot = ledger.snapshot().get(0).activities().get(0);
        check(snapshot.recreationCount() == 1, "configuration recreation should be counted");
        check(snapshot.configurationCount() == 2, "both configuration events should be counted");
        check(snapshot.lastConfigurationToken().equals("config-port"),
                "last configuration token should be retained");
        expectUnknownToken(ledger, launch.activityToken());
    }

    private static void testProcessGenerationRecreationRewritesResultCaller() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision caller = ledger.launch(request(0, "Caller", LaunchMode.STANDARD, 0, null, 3));
        ledger.saveInstanceState(
                caller.activityToken(),
                new SavedActivityState(1, Map.of("route", "caller-state")));
        LaunchDecision callee = ledger.launch(requestForResult(
                0,
                "Callee",
                LaunchMode.STANDARD,
                0,
                caller.taskId(),
                3,
                "",
                9));

        List<ActivityRecreation> recreations = ledger.recreateProcessGeneration(
                0,
                "guest.example",
                "guest.example:main",
                3,
                4);
        check(recreations.size() == 2, "both process Activities should be recreated");
        String currentCallerToken = recreations.get(0).currentActivityToken();
        String currentCalleeToken = recreations.get(1).currentActivityToken();
        check(ledger.activityCount() == 2, "recreation must preserve Activity count");
        check(ledger.taskCount() == 1, "recreation must preserve the task");
        check(ledger.savedInstanceState(currentCallerToken).orElseThrow().version() == 1,
                "saved caller state should survive process restart");
        check(ledger.finishWithResult(currentCalleeToken, -1, "result-after-restart"),
                "recreated callee should finish");
        List<ActivityResultDelivery> results = ledger.drainActivityResults(currentCallerToken);
        check(results.size() == 1, "result should follow recreated caller token");
        check(results.get(0).callerActivityToken().equals(currentCallerToken),
                "caller token reference should be rewritten");
        check(results.get(0).calleeActivityToken().equals(currentCalleeToken),
                "callee token reference should be rewritten");
        expectUnknownToken(ledger, caller.activityToken());
        expectUnknownToken(ledger, callee.activityToken());
    }

    private static void testProcessRestartDropsStaleTransportTokens() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision caller = ledger.launch(requestWithRoute(
                0, "Caller", LaunchMode.STANDARD, 0, null, 3, "route-caller"));
        ledger.launch(requestWithRoute(
                0,
                "Caller",
                LaunchMode.SINGLE_TOP,
                LaunchFlags.SINGLE_TOP,
                caller.taskId(),
                3,
                "route-stale-new-intent"));
        LaunchDecision callee = ledger.launch(requestForResult(
                0,
                "RemoteCallee",
                LaunchMode.STANDARD,
                0,
                caller.taskId(),
                9,
                "",
                21));
        check(ledger.finishWithResult(callee.activityToken(), -1, "route-stale-result"),
                "callee should queue a result before caller restart");
        check(ledger.pendingActivityResultCount(caller.activityToken()) == 1,
                "caller should have one queued result before restart");

        List<ActivityRecreation> recreations = ledger.recreateProcessGeneration(
                0, "guest.example", "guest.example:main", 3, 4);
        check(recreations.size() == 1, "only the stale caller process should recreate");
        String currentCallerToken = recreations.get(0).currentActivityToken();
        check(ledger.drainNewIntents(currentCallerToken).isEmpty(),
                "old-generation new-Intent route tokens must be dropped");
        check(ledger.drainActivityResults(currentCallerToken).isEmpty(),
                "old-generation result route tokens must be dropped");
    }

    private static void testConfigurationRecreationPreservesTransportTokens() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision launch = ledger.launch(requestWithRoute(
                0, "A", LaunchMode.STANDARD, 0, null, 5, "route-launch"));
        ledger.launch(requestWithRoute(
                0,
                "A",
                LaunchMode.SINGLE_TOP,
                LaunchFlags.SINGLE_TOP,
                launch.taskId(),
                5,
                "route-config-new-intent"));
        ConfigurationDecision recreation = ledger.handleConfigurationChange(
                launch.activityToken(), "config-density", false);
        List<NewIntentDelivery> deliveries = ledger.drainNewIntents(
                recreation.currentActivityToken());
        check(deliveries.size() == 1,
                "same-generation configuration recreation should preserve pending new Intent");
        check(deliveries.get(0).routeToken().equals("route-config-new-intent"),
                "configuration recreation must preserve the route token");
        check(deliveries.get(0).activityToken().equals(recreation.currentActivityToken()),
                "configuration recreation must rewrite the Activity target token");
    }

    private static void testSavedStateBoundsAndVersionCollision() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision launch = ledger.launch(request(0, "A", LaunchMode.STANDARD, 0, null, 1));
        SavedActivityState first = new SavedActivityState(1, Map.of("key", "value"));
        check(ledger.saveInstanceState(launch.activityToken(), first), "state should be stored");
        check(!ledger.saveInstanceState(launch.activityToken(), first),
                "identical state and version should be idempotent");
        try {
            ledger.saveInstanceState(
                    launch.activityToken(),
                    new SavedActivityState(1, Map.of("key", "different")));
            throw new AssertionError("version collision should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("collision"), "collision should be explicit");
        }
        try {
            new SavedActivityState(1, Map.of("key", "x".repeat(8193)));
            throw new AssertionError("oversized saved state should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("too long"), "size failure should be explicit");
        }
    }

    private static void testFiveHundredLaunchesWithoutLedgerLeak() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        for (int index = 0; index < 500; index++) {
            LaunchDecision launch = ledger.launch(request(
                    0,
                    "A",
                    LaunchMode.SINGLE_TOP,
                    LaunchFlags.NEW_TASK,
                    null,
                    1));
            check(ledger.finish(launch.activityToken()), "finish should remove launched Activity");
        }
        check(ledger.activityCount() == 0, "no Activity records may leak");
        check(ledger.taskCount() == 0, "no empty Task records may leak");
    }

    private static LaunchRequest request(
            int user,
            String component,
            LaunchMode mode,
            int flags,
            Integer callerTask,
            long generation) {
        return new LaunchRequest(
                new ActivityIdentity(user, "guest.example", component),
                "guest.example",
                mode,
                flags,
                callerTask,
                "guest.example:main",
                generation,
                "route-" + user + "-" + component + "-" + generation,
                "",
                -1);
    }

    private static LaunchRequest requestWithRoute(
            int user,
            String component,
            LaunchMode mode,
            int flags,
            Integer callerTask,
            long generation,
            String routeToken) {
        return new LaunchRequest(
                new ActivityIdentity(user, "guest.example", component),
                "guest.example",
                mode,
                flags,
                callerTask,
                "guest.example:main",
                generation,
                routeToken,
                "",
                -1);
    }

    private static LaunchRequest requestForResult(
            int user,
            String component,
            LaunchMode mode,
            int flags,
            Integer callerTask,
            long generation,
            String resultWho,
            int requestCode) {
        return new LaunchRequest(
                new ActivityIdentity(user, "guest.example", component),
                "guest.example",
                mode,
                flags,
                callerTask,
                "guest.example:main",
                generation,
                "route-result-" + user + "-" + component + "-" + generation,
                resultWho,
                requestCode);
    }

    private static void expectUnknownToken(ActivityTaskLedger ledger, String staleToken) {
        try {
            ledger.savedInstanceState(staleToken);
            throw new AssertionError("stale token should be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("Unknown activity token"),
                    "stale token failure should be explicit");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
