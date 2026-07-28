package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.broker.BrokerStateStore;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.ActivityResultIntentSnapshot;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import java.nio.file.Files;
import java.nio.file.Path;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;

/** Production-adapter regression tests for broker-owned B2 Activity routing and lifecycle state. */
public final class BrokerActivityRuntimeSelfTest {
    private BrokerActivityRuntimeSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path checkpoint = Files.createTempDirectory("broker-activity-").resolve("tasks.bin");
        BrokerStateStore state = new BrokerStateStore();
        BrokerActivityRuntime runtime = new BrokerActivityRuntime(state);
        runtime.configureCheckpointStore(checkpoint);
        GuestSession session = session("s1", 1, SessionState.READY);
        Bundle prepared = prepared(session);
        Bundle request = new Bundle(prepared);
        request.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.MainActivity");
        request.putString(RuntimeKeys.ACTIVITY_LAUNCH_MODE, "SINGLE_TOP");

        Bundle launch = runtime.launch(session, "com.example.MainActivity", prepared, request);
        String route = launch.getString(RuntimeKeys.ROUTE_TOKEN, "");
        String activity = launch.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
        check(!route.isEmpty(), "route token missing");
        check(!activity.isEmpty(), "activity token missing");
        check(runtime.taskCount() == 1 && runtime.activityCount() == 1, "ledger launch not wired");
        ActivityTaskResult running = runtime.taskOperation(
                session, taskRequest(session, ActivityTaskRequest.QUERY_RUNNING));
        check(running.successful() && running.tasks().size() == 1,
                "running-task query should expose owned task");
        check(running.tasks().get(0).taskId() == launch.getInt(RuntimeKeys.TASK_ID, 0),
                "running-task query should preserve task identity");
        check(running.tasks().get(0).packageRevision().equals(session.packageRevision()),
                "running-task query should bind task to APK revision");
        ActivityTaskResult checkpointStatus = runtime.taskOperation(
                session, taskRequest(session, ActivityTaskRequest.CHECKPOINT_STATUS));
        check("PERSISTED".equals(checkpointStatus.checkpointStatus()),
                "launch should persist task checkpoint");

        BrokerActivityRuntime restoredRuntime = new BrokerActivityRuntime(new BrokerStateStore());
        check(restoredRuntime.configureCheckpointStore(checkpoint).restoredTaskCount() == 1,
                "Broker restart should restore persisted task");
        ActivityTaskResult restoredStatus = restoredRuntime.taskOperation(
                session, taskRequest(session, ActivityTaskRequest.CHECKPOINT_STATUS));
        check(restoredStatus.restoredActivityCount() == 1,
                "restored Broker should report restored Activity count");

        Bundle granted = runtime.consume(route, session);
        check("ROUTE_GRANTED".equals(granted.getString(RuntimeKeys.STATUS)), "route not granted");
        expectFailure(() -> runtime.consume(route, session), "route replay must fail");

        event(runtime, session, activity, "CREATED");
        event(runtime, session, activity, "STARTED");
        event(runtime, session, activity, "RESUMED");
        event(runtime, session, activity, "PAUSED");
        event(runtime, session, activity, "STOPPED");

        ActivityResultResult registration = runtime.resultOperation(session, resultRequest(
                session, ActivityResultRequest.REGISTER, activity, "broker-registry", 0,
                ActivityResultIntentSnapshot.empty()));
        check(registration.successful() && registration.assignedRequestCode() == 0,
                "Broker should allocate registry-backed request code");
        Bundle resultLaunchRequest = new Bundle(prepared);
        resultLaunchRequest.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.ResultActivity");
        resultLaunchRequest.putInt(RuntimeKeys.CALLER_TASK_ID, launch.getInt(RuntimeKeys.TASK_ID, 0));
        resultLaunchRequest.putInt(RuntimeKeys.REQUEST_CODE, registration.assignedRequestCode());
        resultLaunchRequest.putString(RuntimeKeys.RESULT_WHO, "fragment:broker");
        resultLaunchRequest.putString(RuntimeKeys.ACTIVITY_RESULT_KEY, "broker-registry");
        resultLaunchRequest.putString(RuntimeKeys.INTENT_SENDER_TOKEN, "broker-intent-sender");
        Bundle resultLaunch = runtime.launch(
                session, "com.example.ResultActivity", prepared, resultLaunchRequest);
        String resultActivity = resultLaunch.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
        ActivityResultResult finished = runtime.resultOperation(session, resultRequest(
                session, ActivityResultRequest.FINISH, resultActivity, "", -1,
                ActivityResultIntentSnapshot.fromMap(
                        "broker.RESULT", "content://broker/result", "text/plain", "",
                        1, "", java.util.Map.of("payload", "ok"))));
        check(finished.successful() && finished.changed(),
                "Broker should finish virtual callee with typed Result Intent");
        ActivityResultResult drained = runtime.resultOperation(session, resultRequest(
                session, ActivityResultRequest.DRAIN, activity, "", 0,
                ActivityResultIntentSnapshot.empty()));
        check(drained.successful() && drained.results().size() == 1,
                "Broker should drain one virtual Activity result");
        check(drained.results().get(0).registryKey().equals("broker-registry")
                        && drained.results().get(0).intentSenderToken().equals("broker-intent-sender")
                        && drained.results().get(0).resultIntent().extras().get("payload").equals("ok"),
                "Broker result metadata or typed Intent payload changed");

        Bundle secondRequest = new Bundle(prepared);
        secondRequest.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.SecondActivity");
        secondRequest.putInt(RuntimeKeys.ACTIVITY_FLAGS,
                com.warden.controlledsandbox.framework.activity.LaunchFlags.NEW_TASK
                        | com.warden.controlledsandbox.framework.activity.LaunchFlags.MULTIPLE_TASK);
        Bundle secondLaunch = runtime.launch(
                session, "com.example.SecondActivity", prepared, secondRequest);
        int secondTaskId = secondLaunch.getInt(RuntimeKeys.TASK_ID, 0);
        ActivityTaskResult movedBack = runtime.taskOperation(session, taskMutation(
                session, ActivityTaskRequest.MOVE_TO_BACK, secondTaskId, ""));
        check(movedBack.successful() && movedBack.changed(),
                "MOVE_TO_BACK should reorder an owned foreground task");
        ActivityTaskResult removedSecond = runtime.taskOperation(session, taskMutation(
                session, ActivityTaskRequest.FINISH_AND_REMOVE_TASK, 0,
                secondLaunch.getString(RuntimeKeys.ACTIVITY_TOKEN, "")));
        check(removedSecond.successful() && removedSecond.changed(),
                "finishAndRemoveTask should remove an owned task");

        Bundle stateEvent = baseEvent(session, activity, "SAVE_STATE");
        stateEvent.putLong(RuntimeKeys.SAVED_STATE_VERSION, 1);
        stateEvent.putString(RuntimeKeys.SAVED_STATE_PREFIX + "screen", "home");
        check("ACTIVITY_EVENT_APPLIED".equals(runtime.event(session, stateEvent).getString(RuntimeKeys.STATUS)),
                "saved state event failed");

        GuestSession foreign = new GuestSession("foreign", "com.example", 2, "com.example", 1,
                1, SessionState.READY, 0, "");
        expectFailure(() -> runtime.event(foreign, baseEvent(foreign, activity, "DESTROYED")),
                "foreign virtual user must not mutate activity");

        GuestSession recovered = session("s1", 2, SessionState.READY);
        runtime.recreate(session, recovered);
        expectFailure(() -> runtime.event(session, baseEvent(session, activity, "DESTROYED")),
                "stale generation must be rejected after recreation");
        runtime.invalidate(recovered);
        check(runtime.activityCount() == 0, "invalidation leaked activity");
        check(runtime.pendingRouteCount() == 0, "invalidation leaked route");

        Path blockedParent = Files.createTempDirectory("broker-activity-rollback-")
                .resolve("not-a-directory");
        Files.writeString(blockedParent, "blocked");
        BrokerActivityRuntime rollbackRuntime = new BrokerActivityRuntime(new BrokerStateStore());
        rollbackRuntime.configureCheckpointStore(blockedParent.resolve("tasks.bin"));
        GuestSession rollbackSession = session("rollback-session", 1, SessionState.READY);
        Bundle rollbackPrepared = prepared(rollbackSession);
        Bundle rollbackLaunch = new Bundle(rollbackPrepared);
        rollbackLaunch.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.RollbackActivity");
        expectFailure(() -> rollbackRuntime.launch(
                rollbackSession, "com.example.RollbackActivity", rollbackPrepared, rollbackLaunch),
                "checkpoint write failure must reject Activity launch");
        check(rollbackRuntime.taskCount() == 0 && rollbackRuntime.activityCount() == 0
                        && rollbackRuntime.pendingRouteCount() == 0,
                "failed checkpoint write must roll back ledger and route state exactly");

        Path malformedFile = Files.createTempDirectory("broker-activity-malformed-")
                .resolve("tasks.bin");
        com.warden.controlledsandbox.framework.activity.ActivityTaskLedger malformedSource =
                new com.warden.controlledsandbox.framework.activity.ActivityTaskLedger();
        malformedSource.launch(new com.warden.controlledsandbox.framework.activity.LaunchRequest(
                new com.warden.controlledsandbox.framework.activity.ActivityIdentity(
                        0, "com.example", "com.example.MalformedActivity"),
                "com.example",
                com.warden.controlledsandbox.framework.activity.LaunchMode.STANDARD,
                com.warden.controlledsandbox.framework.activity.LaunchFlags.NEW_TASK,
                null, "com.example", 1, "malformed-route", "", -1));
        var validCheckpoint = malformedSource.checkpoint();
        var duplicatedTask = validCheckpoint.tasks().get(0);
        var malformedCheckpoint = new com.warden.controlledsandbox.framework.activity.ActivityTaskCheckpoint(
                validCheckpoint.schemaVersion(), validCheckpoint.nextTaskId(),
                validCheckpoint.nextNewIntentSequence(), validCheckpoint.nextConfigurationSequence(),
                validCheckpoint.nextActivationSequence(), validCheckpoint.transportDeliveryCount(),
                java.util.List.of(duplicatedTask, duplicatedTask), validCheckpoint.recentTasks());
        new ActivityTaskCheckpointStore(malformedFile).save(malformedCheckpoint);
        BrokerActivityRuntime malformedRuntime = new BrokerActivityRuntime(new BrokerStateStore());
        malformedRuntime.configureCheckpointStore(malformedFile);
        check(malformedRuntime.taskCount() == 0 && malformedRuntime.activityCount() == 0
                        && malformedRuntime.checkpointStatus().startsWith("QUARANTINED:")
                        && Files.isRegularFile(malformedFile.resolveSibling("tasks.bin.corrupt")),
                "failed restore must roll back partial ledger state and quarantine checkpoint");

        Path cleanupDirectory = Files.createTempDirectory("broker-activity-cleanup-rollback-");
        Path cleanupCheckpoint = cleanupDirectory.resolve("tasks.bin");
        BrokerActivityRuntime cleanupRuntime = new BrokerActivityRuntime(new BrokerStateStore());
        cleanupRuntime.configureCheckpointStore(cleanupCheckpoint);
        GuestSession cleanupSession = session("cleanup-session", 1, SessionState.READY);
        Bundle cleanupPrepared = prepared(cleanupSession);
        Bundle cleanupRequest = new Bundle(cleanupPrepared);
        cleanupRequest.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.CleanupActivity");
        cleanupRuntime.launch(
                cleanupSession, "com.example.CleanupActivity", cleanupPrepared, cleanupRequest);
        Files.delete(cleanupCheckpoint);
        Files.delete(cleanupDirectory);
        Files.writeString(cleanupDirectory, "blocked");
        expectFailure(() -> cleanupRuntime.clearPackageInstance(1, "com.example"),
                "checkpoint failure must reject package-instance cleanup");
        check(cleanupRuntime.taskCount() == 1 && cleanupRuntime.activityCount() == 1,
                "failed cleanup checkpoint must restore the previous ledger state");

        System.out.println("PASS broker Activity production adapter self-test");
    }

    private static ActivityResultRequest resultRequest(
            GuestSession session,
            String operation,
            String activityToken,
            String registryKey,
            int resultCode,
            ActivityResultIntentSnapshot resultIntent) {
        return new ActivityResultRequest(
                RuntimeProtocol.CURRENT,
                "result-request-" + operation + "-" + activityToken,
                session.sessionId(),
                session.generation(),
                session.virtualUserId(),
                session.packageName(),
                operation,
                activityToken,
                registryKey,
                resultCode,
                resultIntent);
    }

    private static ActivityTaskRequest taskRequest(GuestSession session, String operation) {
        int maxCount = ActivityTaskRequest.QUERY_RUNNING.equals(operation)
                || ActivityTaskRequest.QUERY_RECENT.equals(operation) ? 10 : 0;
        return new ActivityTaskRequest(
                RuntimeProtocol.CURRENT,
                "task-request-" + operation,
                session.sessionId(),
                session.generation(),
                session.virtualUserId(),
                session.packageName(),
                operation,
                0,
                maxCount);
    }

    private static ActivityTaskRequest taskMutation(
            GuestSession session,
            String operation,
            int taskId,
            String activityToken) {
        return new ActivityTaskRequest(
                RuntimeProtocol.CURRENT,
                "task-mutation-" + operation,
                session.sessionId(),
                session.generation(),
                session.virtualUserId(),
                session.packageName(),
                operation,
                taskId,
                0,
                activityToken);
    }

    private static Bundle prepared(GuestSession session) {
        Bundle value = new Bundle();
        value.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        value.putLong(RuntimeKeys.GENERATION, session.generation());
        value.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        value.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        value.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        value.putString(RuntimeKeys.APK_PATH, "/tmp/base.apk");
        value.putString(RuntimeKeys.DATA_ROOT, "/tmp/data");
        return value;
    }

    private static GuestSession session(String sessionId, long generation, SessionState state) {
        return new GuestSession(sessionId, "com.example", 1, "com.example", 0,
                generation, state, 0, "");
    }

    private static void event(BrokerActivityRuntime runtime, GuestSession session,
                              String token, String event) {
        Bundle result = runtime.event(session, baseEvent(session, token, event));
        check("ACTIVITY_EVENT_APPLIED".equals(result.getString(RuntimeKeys.STATUS)), event + " failed");
    }

    private static Bundle baseEvent(GuestSession session, String token, String event) {
        Bundle value = new Bundle();
        value.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        value.putLong(RuntimeKeys.GENERATION, session.generation());
        value.putString(RuntimeKeys.ACTIVITY_TOKEN, token);
        value.putString(RuntimeKeys.ACTIVITY_EVENT, event);
        return value;
    }

    private static void expectFailure(Runnable action, String message) {
        try { action.run(); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
