package com.warden.controlledsandbox.runtime.component.service;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Service;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;

public final class BrokerServiceRuntimeSelfTest {
    private BrokerServiceRuntimeSelfTest() { }

    public static void main(String[] args) {
        BrokerServiceRuntime runtime = new BrokerServiceRuntime();
        GuestSession first = session(1, 1);
        GuestSession otherUser = session(2, 1);
        Bundle otherStartResult = success("SERVICE_STARTED");
        otherStartResult.putInt("onStartCommandResult", Service.START_STICKY);
        runtime.applySuccessfulOperation(otherUser, request(ComponentOperations.START_SERVICE), otherStartResult);
        Bundle start = request(ComponentOperations.START_SERVICE);
        Bundle startResult = success("SERVICE_STARTED");
        startResult.putInt("onStartCommandResult", Service.START_STICKY);
        runtime.applySuccessfulOperation(first, start, startResult);
        check("ACTIVE".equals(startResult.getString(RuntimeKeys.SERVICE_STATE)), "started service not active");
        check("STICKY".equals(startResult.getString(RuntimeKeys.SERVICE_RESTART_MODE)), "restart mode missing");

        Bundle bind = request(ComponentOperations.BIND_SERVICE);
        bind.putString(RuntimeKeys.CONNECTION_ID, "connection-1");
        Bundle bindResult = success("SERVICE_BOUND");
        runtime.applySuccessfulOperation(first, bind, bindResult);
        check(bindResult.getInt(RuntimeKeys.SERVICE_CONNECTION_COUNT, 0) == 1, "connection not registered");

        check(runtime.processDisconnected(first).size() == 1, "disconnect did not affect service");
        check("ACTIVE".equals(otherStartResult.getString(RuntimeKeys.SERVICE_STATE)),
                "second virtual user initial state missing");
        check(runtime.recordCount() == 2, "second virtual user service was cross-contaminated");
        GuestSession second = session(1, 2);
        check(runtime.processRecovered(first, second).size() == 1, "sticky service not recovered");
        Bundle rebound = request(ComponentOperations.BIND_SERVICE);
        rebound.putString(RuntimeKeys.CONNECTION_ID, "connection-2");
        runtime.applySuccessfulOperation(second, rebound, success("SERVICE_BOUND"));

        Bundle stop = request(ComponentOperations.STOP_SERVICE);
        Bundle stopResult = success("SERVICE_STOP_REQUESTED");
        runtime.applySuccessfulOperation(second, stop, stopResult);
        check("ACTIVE".equals(stopResult.getString(RuntimeKeys.SERVICE_STATE)),
                "bound service should remain active after stop");

        Bundle unbind = request(ComponentOperations.UNBIND_SERVICE);
        unbind.putString(RuntimeKeys.CONNECTION_ID, "connection-2");
        Bundle unbindResult = success("SERVICE_UNBOUND_DESTROYED");
        runtime.applySuccessfulOperation(second, unbind, unbindResult);
        check("DESTROYED".equals(unbindResult.getString(RuntimeKeys.SERVICE_STATE)),
                "service should settle after final owner disappears");

        Bundle restartResult = success("SERVICE_STARTED");
        restartResult.putInt("onStartCommandResult", Service.START_NOT_STICKY);
        runtime.applySuccessfulOperation(second, start, restartResult);
        check("ACTIVE".equals(restartResult.getString(RuntimeKeys.SERVICE_STATE)),
                "destroyed service must be restartable");
        check(runtime.invalidate(second) == 1 && runtime.recordCount() == 1,
                "instance invalidation crossed virtual-user boundary");
        check(runtime.invalidate(otherUser) == 1 && runtime.recordCount() == 0,
                "second virtual-user service leaked");
        System.out.println("PASS broker Service production registry self-test");
    }

    private static GuestSession session(int userId, long generation) {
        return new GuestSession("s-" + userId, "com.example", userId, "com.example:remote", 0,
                generation, SessionState.READY, 0, "");
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
}
