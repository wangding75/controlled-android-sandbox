package com.warden.controlledsandbox.runtime.component.service;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Service;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.service.ServiceRuntimeRegistry;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;

import java.util.ArrayList;

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
        start.putString(RuntimeKeys.URI, "content://com.example.sync/items/7");
        start.putString(RuntimeKeys.BROADCAST_MIME_TYPE, "text/plain");
        start.putStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES,
                new ArrayList<>(java.util.Arrays.asList("com.example.SYNC", "com.example.RETRY")));
        start.putInt(RuntimeKeys.ACTIVITY_FLAGS, 0x12000000);
        Bundle startExtras = new Bundle();
        startExtras.putString("requestId", "sync-7");
        start.putBundle(RuntimeKeys.INTENT_EXTRAS, startExtras);
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
        secondStart.putString(RuntimeKeys.URI, "content://com.example.full/9");
        secondStart.putString(RuntimeKeys.BROADCAST_MIME_TYPE, "application/json");
        secondStart.putStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES,
                new ArrayList<>(java.util.Arrays.asList("full.category")));
        secondStart.putInt(RuntimeKeys.ACTIVITY_FLAGS, 0x20000000);
        Bundle secondExtras = new Bundle();
        secondExtras.putString("nonce", "n-9");
        secondStart.putBundle(RuntimeKeys.INTENT_EXTRAS, secondExtras);
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
        Bundle retained = runtime.recoveryIntent(runtime.recovering(first).get(0));
        check(retained != null
                        && "ACTION_LATEST".equals(retained.getString(ComponentOperations.ACTION, ""))
                        && "content://com.example.full/9".equals(retained.getString(RuntimeKeys.URI, ""))
                        && "application/json".equals(retained.getString(RuntimeKeys.BROADCAST_MIME_TYPE, ""))
                        && retained.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0) == 0x20000000
                        && "n-9".equals(retained.getBundle(RuntimeKeys.INTENT_EXTRAS)
                        .getString("nonce", ""))
                        && retained.getStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES).size() == 1,
                "full redelivery Intent envelope was truncated");
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
        testFrameworkRecovery();
        System.out.println("PASS broker Service production registry self-test");
    }

    private static void testFrameworkRecovery() {
        BrokerServiceRuntime transactional = new BrokerServiceRuntime(() -> 2_000L);
        GuestSession transactionalSession = session(8, 1);
        Bundle begin = request(ComponentOperations.FRAMEWORK_SERVICE_EVENT_START_BEGIN);
        begin.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        begin.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_FOREGROUND, true);
        begin.putInt(RuntimeKeys.SERVICE_START_ID, 4);
        begin.putInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK, 0);
        begin.putLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS, 5_000L);
        Bundle beginResult = success("FRAMEWORK_SERVICE_EVENT_RECORDED");
        transactional.beginFrameworkStart(transactionalSession, begin, beginResult);
        check("PENDING".equals(beginResult.getString(RuntimeKeys.SERVICE_FOREGROUND_STATE, "")),
                "framework start did not open a pending foreground transaction");

        Bundle promotion = request(ComponentOperations.SET_SERVICE_FOREGROUND);
        promotion.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        promotion.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, true);
        promotion.putInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, 0);
        promotion.putInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, 42);
        Bundle promotionResult = success("FRAMEWORK_SERVICE_FOREGROUND");
        transactional.applySuccessfulOperation(transactionalSession, promotion, promotionResult);
        check(promotionResult.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, false),
                "foreground promotion before callback completion was lost");

        Bundle complete = request(ComponentOperations.FRAMEWORK_SERVICE_EVENT_START);
        complete.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        complete.putInt(RuntimeKeys.SERVICE_START_ID, 4);
        Bundle completeResult = success("FRAMEWORK_SERVICE_EVENT_RECORDED");
        completeResult.putInt("onStartCommandResult", Service.START_STICKY);
        transactional.completeFrameworkStart(transactionalSession, complete, completeResult);
        check("STICKY".equals(completeResult.getString(RuntimeKeys.SERVICE_RESTART_MODE, ""))
                        && completeResult.getInt(RuntimeKeys.SERVICE_LAST_START_ID, -1) == 4,
                "framework callback completion lost restart mode or platform start id");

        BrokerServiceRuntime runtime = new BrokerServiceRuntime(() -> 2_000L);
        GuestSession stale = session(9, 1);
        Bundle start = request(ComponentOperations.START_SERVICE);
        start.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        Bundle result = success("FRAMEWORK_SERVICE_EVENT_RECORDED");
        result.putInt("onStartCommandResult", Service.START_REDELIVER_INTENT);
        runtime.applySuccessfulOperation(stale, start, result);
        check(runtime.snapshot().get(0).frameworkOwned(),
                "framework-owned service marker was not persisted");
        runtime.processDisconnected(stale);
        ServiceRuntimeRegistry.Snapshot recovering = runtime.recovering(stale).get(0);
        GuestSession current = session(9, 2);
        Bundle recovery = request(ComponentOperations.RECOVER_FRAMEWORK_SERVICE);
        recovery.putString(ComponentOperations.ACTION, "ACTION_RECOVER");
        Bundle recoveryResult = success("FRAMEWORK_SERVICE_RECOVERED");
        recoveryResult.putInt(RuntimeKeys.SERVICE_START_ID, 7);
        recoveryResult.putInt("onStartCommandResult", Service.START_STICKY);
        ServiceRuntimeRegistry.Snapshot recovered = runtime.completeFrameworkRecovery(
                stale, current, recovering, recovery, recoveryResult);
        check(recovered.frameworkOwned() && recovered.generation() == 2
                && recovered.lastStartId() == 7
                        && recovered.restartMode() == ServiceRuntimeRegistry.RestartMode.STICKY
                        && "ACTION_RECOVER".equals(recovered.lastStartAction()),
                "framework-owned recovery transaction lost generation or start identity");

        BrokerServiceRuntime foregroundRecovery = new BrokerServiceRuntime(() -> 3_000L);
        GuestSession oldForeground = session(10, 1);
        Bundle foregroundStart = request(ComponentOperations.START_FOREGROUND_SERVICE);
        foregroundStart.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        foregroundStart.putInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK, 0);
        Bundle foregroundStartResult = success("FRAMEWORK_SERVICE_EVENT_RECORDED");
        foregroundStartResult.putInt("onStartCommandResult", Service.START_STICKY);
        foregroundRecovery.applySuccessfulOperation(oldForeground, foregroundStart, foregroundStartResult);
        Bundle foregroundPromotion = request(ComponentOperations.SET_SERVICE_FOREGROUND);
        foregroundPromotion.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        foregroundPromotion.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, true);
        foregroundPromotion.putInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, 0);
        foregroundPromotion.putInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, 9);
        foregroundRecovery.applySuccessfulOperation(oldForeground, foregroundPromotion,
                success("FRAMEWORK_SERVICE_FOREGROUND"));
        foregroundRecovery.processDisconnected(oldForeground);
        ServiceRuntimeRegistry.Snapshot foregroundRecovering =
                foregroundRecovery.recovering(oldForeground).get(0);
        GuestSession newForeground = session(10, 2);
        Bundle foregroundRecoveryRequest = request(ComponentOperations.RECOVER_FRAMEWORK_SERVICE);
        foregroundRecoveryRequest.putBoolean(RuntimeKeys.SERVICE_RECOVERY, true);
        foregroundRecoveryRequest.putInt(RuntimeKeys.SERVICE_START_ID, 5);
        Bundle foregroundRecoveryResult = success("FRAMEWORK_SERVICE_RECOVERED");
        foregroundRecoveryResult.putInt(RuntimeKeys.SERVICE_START_ID, 5);
        foregroundRecoveryResult.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_OBSERVED, true);
        foregroundRecoveryResult.putInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, 0);
        foregroundRecoveryResult.putInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, 10);
        foregroundRecovery.completeFrameworkRecovery(oldForeground, newForeground,
                foregroundRecovering, foregroundRecoveryRequest, foregroundRecoveryResult);
        check(foregroundRecoveryResult.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, false)
                        && "ACTIVE".equals(foregroundRecoveryResult.getString(
                        RuntimeKeys.SERVICE_FOREGROUND_STATE, "")),
                "framework foreground recovery did not commit the observed promotion");
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
