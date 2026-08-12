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
        testForwardResultChain();
        testNoHistoryAndRecentTaskPolicy();
        testRunningRecentMoveAndRemoveQueries();
        testRootActivityQuery();
        testLaunchFlagValidationMatrix();
        testDocumentLaunchModes();
        testFinishMoveBackAndRevisionCleanup();
        testCheckpointRestoreDropsTransportAndPreservesState();
        testRegistryResultIntentAndIntentSender();
        testCheckpointRestoresPendingResultOwnership();
        testExactRollbackState();
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

    private static void testForwardResultChain() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision caller = ledger.launch(request(0, "Caller", LaunchMode.STANDARD, 0, null, 1));
        LaunchDecision middle = ledger.launch(requestForResult(
                0, "Middle", LaunchMode.STANDARD, 0, caller.taskId(), 1, "fragment", 17));
        LaunchDecision leaf = ledger.launch(request(
                0, "Leaf", LaunchMode.STANDARD, LaunchFlags.FORWARD_RESULT,
                middle.taskId(), 1));
        check(ledger.finishWithResult(leaf.activityToken(), -1, "leaf-result"),
                "forwarded leaf should finish");
        List<ActivityResultDelivery> deliveries = ledger.drainActivityResults(caller.activityToken());
        check(deliveries.size() == 1, "forwarded result should reach original caller");
        check(deliveries.get(0).requestCode() == 17, "forwarded request code must be preserved");
        check(deliveries.get(0).calleeActivityToken().equals(leaf.activityToken()),
                "forwarded result must identify terminal callee");
        check(ledger.finish(middle.activityToken()), "middle Activity should still be finishable");
        check(ledger.drainActivityResults(caller.activityToken()).isEmpty(),
                "middle Activity must not emit a second result after forwarding");
    }

    private static void testNoHistoryAndRecentTaskPolicy() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision root = ledger.launch(request(
                0, "Root", LaunchMode.STANDARD, LaunchFlags.NEW_TASK, null, 1));
        LaunchDecision transientActivity = ledger.launch(request(
                0, "Transient", LaunchMode.STANDARD, LaunchFlags.NO_HISTORY,
                root.taskId(), 1));
        ledger.launch(request(0, "Next", LaunchMode.STANDARD, 0, root.taskId(), 1));
        expectUnknownToken(ledger, transientActivity.activityToken());
        List<ActivitySnapshot> stack = ledger.snapshot().get(0).activities();
        check(stack.size() == 2, "NO_HISTORY Activity should leave the back stack");
        check(stack.get(0).identity().componentName().equals("Root"), "root should remain");
        check(stack.get(1).identity().componentName().equals("Next"), "next should be top");

        LaunchDecision retainedDocument = ledger.launch(request(
                0, "Document", LaunchMode.STANDARD,
                LaunchFlags.NEW_TASK | LaunchFlags.NEW_DOCUMENT | LaunchFlags.RETAIN_IN_RECENTS,
                null, 1));
        check(ledger.finish(retainedDocument.activityToken()), "retained document should finish");
        List<TaskQuerySnapshot> recent = ledger.recentTasks(0, "guest.example", 10);
        check(recent.stream().anyMatch(task -> task.taskId() == retainedDocument.taskId()
                        && !task.active() && task.retainInRecents()),
                "retained document should remain in recent-task history");

        LaunchDecision excluded = ledger.launch(request(
                0, "Excluded", LaunchMode.STANDARD,
                LaunchFlags.NEW_TASK | LaunchFlags.MULTIPLE_TASK | LaunchFlags.EXCLUDE_FROM_RECENTS,
                null, 1));
        check(ledger.finish(excluded.activityToken()), "excluded task should finish");
        check(ledger.recentTasks(0, "guest.example", 10).stream()
                        .noneMatch(task -> task.taskId() == excluded.taskId()),
                "EXCLUDE_FROM_RECENTS task must not be returned");
    }

    private static void testRunningRecentMoveAndRemoveQueries() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision first = ledger.launch(request(
                0, "A", LaunchMode.STANDARD, LaunchFlags.NEW_TASK, null, 1));
        LaunchDecision second = ledger.launch(request(
                0, "B", LaunchMode.STANDARD,
                LaunchFlags.NEW_TASK | LaunchFlags.MULTIPLE_TASK, null, 1));
        List<TaskQuerySnapshot> running = ledger.runningTasks(0, "guest.example", 10);
        check(running.size() == 2 && running.get(0).taskId() == second.taskId(),
                "running tasks must be front-first");
        check(ledger.moveTaskToFront(0, "guest.example", first.taskId()),
                "background task should move to front");
        running = ledger.runningTasks(0, "guest.example", 10);
        check(running.get(0).taskId() == first.taskId(), "moved task should be foreground");
        check(running.get(0).moveToFrontCount() >= 2,
                "task activation count should include launch and explicit move");
        try {
            ledger.moveTaskToFront(1, "guest.example", first.taskId());
            throw new AssertionError("foreign virtual user should not move task");
        } catch (SecurityException expected) {
            check(expected.getMessage().contains("TASK_OWNER_MISMATCH"),
                    "task ownership rejection should be explicit");
        }
        check(ledger.removeTask(0, "guest.example", first.taskId()),
                "owned task should be removable");
        check(ledger.runningTasks(0, "guest.example", 10).size() == 1,
                "removed task must disappear from running query");
        check(ledger.recentTasks(0, "guest.example", 10).stream()
                        .noneMatch(task -> task.taskId() == first.taskId()),
                "explicit remove must remove task from recents");
    }

    private static void testRootActivityQuery() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision root = ledger.launch(request(
                0, "Root", LaunchMode.STANDARD, 0, null, 1));
        LaunchDecision child = ledger.launch(request(
                0, "Child", LaunchMode.STANDARD, 0, root.taskId(), 1));
        check(ledger.isRootActivity(root.activityToken()),
                "the first Activity in a task must be reported as root");
        check(!ledger.isRootActivity(child.activityToken()),
                "a non-root Activity must not satisfy onlyRoot");
    }

    private static void testLaunchFlagValidationMatrix() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        expectLaunchFailure(ledger, advancedRequest(
                0, "InvalidMultiple", LaunchMode.STANDARD, LaunchFlags.MULTIPLE_TASK,
                null, 1, "rev-a", DocumentLaunchMode.NONE, ""),
                "MULTIPLE_TASK requires");
        expectLaunchFailure(ledger, advancedRequest(
                0, "InvalidClear", LaunchMode.STANDARD, LaunchFlags.CLEAR_TASK,
                null, 1, "rev-a", DocumentLaunchMode.NONE, ""),
                "CLEAR_TASK requires");
        expectLaunchFailure(ledger, advancedRequest(
                0, "NeverDocument", LaunchMode.STANDARD,
                LaunchFlags.NEW_TASK | LaunchFlags.NEW_DOCUMENT,
                null, 1, "rev-a", DocumentLaunchMode.NEVER, "doc-never"),
                "NEVER forbids");
        expectLaunchFailure(ledger, advancedRequest(
                0, "MissingDocumentKey", LaunchMode.STANDARD, LaunchFlags.NEW_TASK,
                null, 1, "rev-a", DocumentLaunchMode.INTO_EXISTING, ""),
                "requires a stable documentKey");
    }

    private static void testDocumentLaunchModes() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision first = ledger.launch(advancedRequest(
                0, "Document", LaunchMode.STANDARD, LaunchFlags.NEW_TASK,
                null, 1, "rev-doc", DocumentLaunchMode.INTO_EXISTING, "content://item/7"));
        ledger.launch(advancedRequest(
                0, "Detail", LaunchMode.STANDARD, 0,
                first.taskId(), 1, "rev-doc", DocumentLaunchMode.NONE, ""));
        LaunchDecision reused = ledger.launch(advancedRequest(
                0, "Document", LaunchMode.STANDARD, LaunchFlags.NEW_TASK,
                null, 1, "rev-doc", DocumentLaunchMode.INTO_EXISTING, "content://item/7"));
        check(reused.taskId() == first.taskId(), "INTO_EXISTING should reuse matching document task");
        check(reused.removedActivityCount() == 1, "INTO_EXISTING should clear document task to root");
        TaskQuerySnapshot reusedSnapshot = ledger.runningTasks(
                0, "guest.example", "rev-doc", 10).get(0);
        check(reusedSnapshot.documentTask(), "document task marker should be retained");
        check(reusedSnapshot.documentLaunchMode() == DocumentLaunchMode.INTO_EXISTING,
                "document launch mode should be queryable");
        check(reusedSnapshot.documentKey().equals("content://item/7"),
                "document identity should be queryable");

        LaunchDecision alwaysOne = ledger.launch(advancedRequest(
                0, "Always", LaunchMode.STANDARD, 0,
                null, 1, "rev-doc", DocumentLaunchMode.ALWAYS, "content://item/8"));
        LaunchDecision alwaysTwo = ledger.launch(advancedRequest(
                0, "Always", LaunchMode.STANDARD, 0,
                null, 1, "rev-doc", DocumentLaunchMode.ALWAYS, "content://item/8"));
        check(alwaysOne.taskId() != alwaysTwo.taskId(),
                "ALWAYS document mode should create a distinct task");
    }

    private static void testFinishMoveBackAndRevisionCleanup() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision root = ledger.launch(advancedRequest(
                0, "Root", LaunchMode.STANDARD, LaunchFlags.NEW_TASK,
                null, 2, "rev-current", DocumentLaunchMode.NONE, ""));
        LaunchDecision middle = ledger.launch(advancedRequest(
                0, "Middle", LaunchMode.STANDARD, 0,
                root.taskId(), 2, "rev-current", DocumentLaunchMode.NONE, ""));
        ledger.launch(advancedRequest(
                0, "Top", LaunchMode.STANDARD, 0,
                root.taskId(), 2, "rev-current", DocumentLaunchMode.NONE, ""));
        check(ledger.finishAffinity(middle.activityToken()) == 2,
                "finishAffinity should remove the selected Activity and all above it");
        check(ledger.snapshot().get(0).activities().size() == 1,
                "finishAffinity should retain the lower affinity stack");

        LaunchDecision other = ledger.launch(advancedRequest(
                0, "Other", LaunchMode.STANDARD,
                LaunchFlags.NEW_TASK | LaunchFlags.MULTIPLE_TASK,
                null, 2, "rev-current", DocumentLaunchMode.NONE, ""));
        check(ledger.moveTaskToBack(0, "guest.example", "rev-current", other.taskId()),
                "foreground task should move behind another task");
        List<TaskQuerySnapshot> running = ledger.runningTasks(
                0, "guest.example", "rev-current", 10);
        check(running.get(running.size() - 1).taskId() == other.taskId(),
                "moved-back task should be last in front-first query order");
        check(ledger.finishAndRemoveTask(other.activityToken()),
                "finishAndRemoveTask should delete the whole task");

        LaunchDecision stale = ledger.launch(advancedRequest(
                0, "Stale", LaunchMode.STANDARD,
                LaunchFlags.NEW_TASK | LaunchFlags.MULTIPLE_TASK,
                null, 2, "rev-old", DocumentLaunchMode.NONE, ""));
        try {
            ledger.moveTaskToFront(0, "guest.example", "rev-current", stale.taskId());
            throw new AssertionError("revision mismatch should reject task mutation");
        } catch (SecurityException expected) {
            check(expected.getMessage().contains("TASK_REVISION_MISMATCH"),
                    "revision ownership rejection should be explicit");
        }
        check(ledger.clearPackageRevision(0, "guest.example", "rev-current") == 1,
                "old package revision task should be removed");
        check(ledger.runningTasks(0, "guest.example", "rev-old", 10).isEmpty(),
                "old revision query should have no live tasks after cleanup");
    }

    private static void testCheckpointRestoreDropsTransportAndPreservesState() {
        ActivityTaskLedger source = new ActivityTaskLedger();
        LaunchDecision root = source.launch(request(
                0, "Root", LaunchMode.STANDARD, LaunchFlags.NEW_TASK, null, 4));
        LaunchDecision top = source.launch(request(
                0, "Top", LaunchMode.STANDARD, 0, root.taskId(), 4));
        source.saveInstanceState(top.activityToken(),
                new SavedActivityState(3, Map.of("screen", "details")));
        source.launch(requestWithRoute(
                0, "Top", LaunchMode.SINGLE_TOP, LaunchFlags.SINGLE_TOP,
                root.taskId(), 4, "route-will-be-dropped"));
        ActivityTaskCheckpoint checkpoint = source.checkpoint();
        check(checkpoint.transportDeliveryCount() == 1,
                "checkpoint should account for dropped route-bound delivery");

        ActivityTaskLedger restored = new ActivityTaskLedger();
        ActivityTaskRestoreOutcome outcome = restored.restore(checkpoint);
        check(outcome.restoredTaskCount() == 1 && outcome.restoredActivityCount() == 2,
                "checkpoint should restore task stack");
        check(outcome.droppedTransportDeliveryCount() == 1,
                "restore must report fail-closed transport drops");
        TaskSnapshot restoredTask = restored.snapshot().get(0);
        String restoredTopToken = restoredTask.activities().get(1).token();
        check(restored.savedInstanceState(restoredTopToken).orElseThrow().values()
                        .get("screen").equals("details"),
                "saved state should survive checkpoint restore");
        check(restored.drainNewIntents(restoredTopToken).isEmpty(),
                "route-bound new Intent must not survive Broker checkpoint restore");
        check(restored.adoptRestoredProcessGeneration(
                0, "guest.example", "guest.example:main", 9) == 2,
                "restored activities should adopt current Guest generation once");
        String adoptedTopToken = restored.snapshot().get(0).activities().get(1).token();
        check(restored.processIdentity(adoptedTopToken).processGeneration() == 9,
                "adopted task must bind to current Guest generation");
        check(restored.adoptRestoredProcessGeneration(
                0, "guest.example", "guest.example:main", 10) == 0,
                "restored generation adoption must be one-shot");
    }

    private static void testRegistryResultIntentAndIntentSender() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision caller = ledger.launch(request(
                0, "RegistryCaller", LaunchMode.STANDARD, LaunchFlags.NEW_TASK, null, 7));
        ActivityResultRegistration registration = ledger.registerActivityResult(
                caller.activityToken(), "profile-editor");
        check(registration.requestCode() == 0, "first registry key should receive request code 0");
        check(ledger.registerActivityResult(caller.activityToken(), "profile-editor").equals(registration),
                "registry key allocation must be idempotent");
        LaunchDecision callee = ledger.launch(new LaunchRequest(
                new ActivityIdentity(0, "guest.example", "RegistryCallee"),
                "guest.example", LaunchMode.STANDARD, 0, caller.taskId(),
                "guest.example:main", 7, "route-registry-result", "fragment:profile",
                registration.requestCode(), "legacy", DocumentLaunchMode.NONE, "",
                registration.key(), "intent-sender-42"));
        ResultIntentSnapshot resultIntent = new ResultIntentSnapshot(
                "guest.RESULT", "content://guest/result/1", "text/plain",
                "guest.example/RegistryCaller", 3, "result clip",
                Map.of("name", "Ada", "count", "2"));
        check(ledger.finishWithResult(callee.activityToken(), -1, resultIntent),
                "typed result should finish callee");
        ActivityResultDelivery delivery = ledger.drainActivityResults(
                caller.activityToken()).get(0);
        check(delivery.registryKey().equals("profile-editor"),
                "Activity Result registry key should be preserved");
        check(delivery.intentSenderToken().equals("intent-sender-42"),
                "Intent Sender ownership token should be preserved");
        check(delivery.resultWho().equals("fragment:profile"),
                "Result Who should be preserved");
        check(delivery.resultIntent().equals(resultIntent),
                "typed Result Intent should survive delivery");
        ResultIntentSnapshot senderResult = new ResultIntentSnapshot(
                "guest.SENDER_RESULT", "content://guest/sender/9", "application/json",
                "guest.example/RegistryCaller", 5, "sender clip", Map.of("source", "intent-sender"));
        check(ledger.deliverActivityResult(caller.activityToken(), "fragment:sender", 9,
                        -1, "pending-intent-9", senderResult),
                "Activity Result PendingIntent should enqueue direct result delivery");
        ActivityResultDelivery senderDelivery = ledger.drainActivityResults(caller.activityToken()).get(0);
        check(senderDelivery.resultWho().equals("fragment:sender")
                        && senderDelivery.requestCode() == 9
                        && senderDelivery.intentSenderToken().equals("pending-intent-9")
                        && senderDelivery.resultIntent().equals(senderResult),
                "direct Activity Result sender metadata must survive ledger delivery");
        check(ledger.unregisterActivityResult(caller.activityToken(), "profile-editor"),
                "registry key should unregister");
    }

    private static void testCheckpointRestoresPendingResultOwnership() {
        ActivityTaskLedger source = new ActivityTaskLedger();
        LaunchDecision caller = source.launch(request(
                0, "RestoreCaller", LaunchMode.STANDARD, LaunchFlags.NEW_TASK, null, 11));
        ActivityResultRegistration registration = source.registerActivityResult(
                caller.activityToken(), "restore-key");
        source.launch(new LaunchRequest(
                new ActivityIdentity(0, "guest.example", "RestoreCallee"),
                "guest.example", LaunchMode.STANDARD, 0, caller.taskId(),
                "guest.example:main", 11, "route-restore-result", "fragment:restore",
                registration.requestCode(), "legacy", DocumentLaunchMode.NONE, "",
                registration.key(), "sender-restore"));
        ActivityTaskCheckpoint checkpoint = source.checkpoint();
        check(checkpoint.transportDeliveryCount() == 0,
                "durable pending result ownership is not a dropped transport delivery");

        ActivityTaskLedger restored = new ActivityTaskLedger();
        restored.restore(checkpoint);
        List<ActivitySnapshot> activities = restored.snapshot().get(0).activities();
        String restoredCaller = activities.stream()
                .filter(value -> value.identity().componentName().equals("RestoreCaller"))
                .findFirst().orElseThrow().token();
        String restoredCallee = activities.stream()
                .filter(value -> value.identity().componentName().equals("RestoreCallee"))
                .findFirst().orElseThrow().token();
        check(restored.activityResultRegistration(restoredCaller, "restore-key")
                        .orElseThrow().requestCode() == registration.requestCode(),
                "registry mapping should survive Broker checkpoint restore");
        check(restored.finishWithResult(restoredCallee, -1,
                        new ResultIntentSnapshot("restore", "", "", "", 0, "", Map.of())),
                "restored callee should finish");
        ActivityResultDelivery delivery = restored.drainActivityResults(restoredCaller).get(0);
        check(delivery.registryKey().equals("restore-key")
                        && delivery.intentSenderToken().equals("sender-restore"),
                "restored result ownership metadata should be delivered");
    }

    private static void testExactRollbackState() {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        LaunchDecision initial = ledger.launch(request(
                0, "RollbackRoot", LaunchMode.STANDARD, LaunchFlags.NEW_TASK, null, 2));
        ledger.registerActivityResult(initial.activityToken(), "rollback-key");
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        ledger.launch(request(
                0, "RollbackChild", LaunchMode.STANDARD, 0, initial.taskId(), 2));
        ledger.finish(initial.activityToken());
        ledger.restoreRollbackState(before);
        check(ledger.activityCount() == 1 && ledger.taskCount() == 1,
                "rollback should restore exact task and Activity counts");
        check(ledger.activityResultRegistration(initial.activityToken(), "rollback-key").isPresent(),
                "rollback should preserve registration and original Activity token");
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

    private static LaunchRequest advancedRequest(
            int user,
            String component,
            LaunchMode mode,
            int flags,
            Integer callerTask,
            long generation,
            String packageRevision,
            DocumentLaunchMode documentMode,
            String documentKey) {
        return new LaunchRequest(
                new ActivityIdentity(user, "guest.example", component),
                "guest.example",
                mode,
                flags,
                callerTask,
                "guest.example:main",
                generation,
                "route-advanced-" + user + "-" + component + "-" + generation,
                "",
                -1,
                packageRevision,
                documentMode,
                documentKey);
    }

    private static void expectLaunchFailure(
            ActivityTaskLedger ledger,
            LaunchRequest request,
            String expectedMessage) {
        try {
            ledger.launch(request);
            throw new AssertionError("invalid launch combination should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(expectedMessage),
                    "launch validation failure should contain: " + expectedMessage);
        }
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
