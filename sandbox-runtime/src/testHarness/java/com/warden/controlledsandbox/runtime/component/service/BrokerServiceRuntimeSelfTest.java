package com.warden.controlledsandbox.runtime.component.service;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Service;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;

public final class BrokerServiceRuntimeSelfTest {
    private BrokerServiceRuntimeSelfTest() { }

    public static void main(String[] args) {
        FakeClock clock = new FakeClock(1_000L);
        BrokerServiceRuntime runtime = new BrokerServiceRuntime(clock);
        GuestSession first = session(1, 1);
        GuestSession otherUser = session(2, 1);
        Bundle otherStartResult = success("SERVICE_STARTED");
        otherStartResult.putInt("onStartCommandResult", Service.START_STICKY);
        runtime.applySuccessfulOperation(otherUser, request(ComponentOperations.START_SERVICE), otherStartResult);

        Bundle start = request(ComponentOperations.START_FOREGROUND_SERVICE);
        start.putString(ComponentOperations.ACTION, "ACTION_SYNC");
        start.putInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK, 0b0110);
        start.putLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS, 5_000L);
        Bundle startResult = success("SERVICE_STARTED");
        startResult.putInt("onStartCommandResult", Service.START_REDELIVER_INTENT);
        runtime.applySuccessfulOperation(first, start, startResult);
        check("ACTIVE".equals(startResult.getString(RuntimeKeys.SERVICE_STATE)), "started service not active");
        check("REDELIVER_INTENT".equals(startResult.getString(RuntimeKeys.SERVICE_RESTART_MODE)), "restart mode missing");
        check(!startResult.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, true)
                        && "PENDING".equals(startResult.getString(RuntimeKeys.SERVICE_FOREGROUND_STATE, "")),
                "foreground request must remain pending until promotion");

        Bundle promote = request(ComponentOperations.SET_SERVICE_FOREGROUND);
        promote.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, true);
        promote.putInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, 0b0010);
        promote.putInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, 17);
        promote.putString(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_TAG, "sync");
        Bundle promoteResult = success("SERVICE_FOREGROUND");
        runtime.applySuccessfulOperation(first, promote, promoteResult);
        check(promoteResult.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, false)
                        && "ACTIVE".equals(promoteResult.getString(RuntimeKeys.SERVICE_FOREGROUND_STATE, ""))
                        && promoteResult.getInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, 0) == 17,
                "foreground state missing");

        Bundle secondStart = request(ComponentOperations.START_SERVICE);
        secondStart.putString(ComponentOperations.ACTION, "ACTION_LATEST");
        Bundle secondStartResult = success("SERVICE_STARTED");
        secondStartResult.putInt("onStartCommandResult", Service.START_REDELIVER_INTENT);
        runtime.applySuccessfulOperation(first, secondStart, secondStartResult);
        check(secondStartResult.getInt(RuntimeKeys.SERVICE_LAST_START_ID, 0) == 2, "start id did not advance");

        Bundle staleStop = request(ComponentOperations.STOP_SERVICE_START_ID);
        staleStop.putInt(RuntimeKeys.SERVICE_START_ID, 1);
        Bundle staleStopResult = success("SERVICE_START_ID_STALE");
        runtime.applySuccessfulOperation(first, staleStop, staleStopResult);
        check(staleStopResult.getInt(RuntimeKeys.SERVICE_START_COUNT, 0) == 2, "stale start id stopped service");

        Bundle bind = request(ComponentOperations.BIND_SERVICE);
        bind.putString(RuntimeKeys.CONNECTION_ID, "connection-1");
        Bundle bindResult = success("SERVICE_BOUND");
        runtime.applySuccessfulOperation(first, bind, bindResult);
        check(bindResult.getInt(RuntimeKeys.SERVICE_CONNECTION_COUNT, 0) == 1, "connection not registered");

        check(runtime.processDisconnected(first).size() == 1, "disconnect did not affect service");
        check(runtime.recovering(first).size() == 1, "redeliver service not marked for recovery");
        check("ACTION_LATEST".equals(runtime.recovering(first).get(0).lastStartAction()), "redelivery action lost");
        check(runtime.recovering(first).get(0).recoverForeground(), "foreground recovery intent lost");
        check(runtime.recordCount() == 2, "second virtual user service was cross-contaminated");
        GuestSession second = session(1, 2);
        clock.advance(100L);
        check(runtime.processRecovered(first, second).size() == 1, "service not recovered");
        check(runtime.snapshot().stream().anyMatch(value -> value.instanceId().equals("u1:com.example")
                        && value.foregroundSnapshot().state().name().equals("PENDING")),
                "foreground recovery did not require a new promotion");

        Bundle rebound = request(ComponentOperations.BIND_SERVICE);
        rebound.putString(RuntimeKeys.CONNECTION_ID, "connection-2");
        runtime.applySuccessfulOperation(second, rebound, success("SERVICE_BOUND"));

        Bundle stop = request(ComponentOperations.STOP_SERVICE);
        Bundle stopResult = success("SERVICE_STOP_REQUESTED");
        runtime.applySuccessfulOperation(second, stop, stopResult);
        check("ACTIVE".equals(stopResult.getString(RuntimeKeys.SERVICE_STATE)),
                "bound service should remain active after stop");
        check(!stopResult.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, true), "foreground state survived stop");

        Bundle unbind = request(ComponentOperations.UNBIND_SERVICE);
        unbind.putString(RuntimeKeys.CONNECTION_ID, "connection-2");
        Bundle unbindResult = success("SERVICE_UNBOUND_DESTROYED");
        runtime.applySuccessfulOperation(second, unbind, unbindResult);
        check("DESTROYED".equals(unbindResult.getString(RuntimeKeys.SERVICE_STATE)),
                "service should settle after final owner disappears");

        Bundle timeoutStart = request(ComponentOperations.START_FOREGROUND_SERVICE);
        timeoutStart.putLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS, 100L);
        Bundle timeoutStartResult = success("SERVICE_STARTED");
        timeoutStartResult.putInt("onStartCommandResult", Service.START_NOT_STICKY);
        runtime.applySuccessfulOperation(second, timeoutStart, timeoutStartResult);
        clock.advance(100L);
        check(runtime.expireForeground().size() == 1, "foreground promotion timeout was not expired");

        var beforeDenied = runtime.snapshot().stream()
                .filter(value -> value.instanceId().equals("u1:com.example"))
                .findFirst().orElseThrow();
        Bundle denied = request(ComponentOperations.START_FOREGROUND_SERVICE);
        denied.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_BACKGROUND_ALLOWED, false);
        boolean deniedThrown = false;
        try {
            runtime.applySuccessfulOperation(second, denied, success("SERVICE_STARTED"));
        } catch (SecurityException expected) {
            deniedThrown = expected.getMessage().contains("BACKGROUND_START_NOT_ALLOWED");
        }
        check(deniedThrown, "background foreground-service start was not rejected");
        var afterDenied = runtime.snapshot().stream()
                .filter(value -> value.instanceId().equals("u1:com.example"))
                .findFirst().orElseThrow();
        check(afterDenied.state() == beforeDenied.state()
                        && afterDenied.startCount() == beforeDenied.startCount(),
                "rejected foreground start mutated Broker service state");

        Bundle restartResult = success("SERVICE_STARTED");
        restartResult.putInt("onStartCommandResult", Service.START_NOT_STICKY);
        runtime.applySuccessfulOperation(second, request(ComponentOperations.START_SERVICE), restartResult);
        check("ACTIVE".equals(restartResult.getString(RuntimeKeys.SERVICE_STATE)),
                "destroyed service must be restartable");
        check(runtime.invalidate(second) == 1 && runtime.recordCount() == 1,
                "instance invalidation crossed virtual-user boundary");
        check(runtime.invalidate(otherUser) == 1 && runtime.recordCount() == 0,
                "second virtual-user service leaked");
        System.out.println("PASS broker Service production registry self-test");
    }

    private static GuestSession session(int userId, long generation) {
        return new GuestSession("s-" + userId + "-" + generation, "com.example", userId,
                "com.example:remote", 0, generation, SessionState.READY, 0, "");
    }

    private static Bundle request(String operation) {
        Bundle value = new Bundle();
        value.putString(ComponentOperations.OPERATION, operation);
        value.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.SyncService");
        return value;
    }

    private static Bundle success(String status) {
        Bundle value = new Bundle();
        value.putString(RuntimeKeys.STATUS, status);
        return value;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeClock implements Clock {
        private long now;
        FakeClock(long now) { this.now = now; }
        @Override public long nowMillis() { return now; }
        void advance(long value) { now += value; }
    }
}
