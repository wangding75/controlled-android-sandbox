package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.broker.BrokerStateStore;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
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

        System.out.println("PASS broker Activity production adapter self-test");
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
