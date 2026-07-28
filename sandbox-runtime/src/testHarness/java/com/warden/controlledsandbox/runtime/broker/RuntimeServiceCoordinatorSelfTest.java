package com.warden.controlledsandbox.runtime.broker;

import android.app.Service;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.warden.controlledsandbox.domain.component.service.ServiceRuntimeRegistry;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.util.ArrayList;
import java.util.List;

public final class RuntimeServiceCoordinatorSelfTest {
    private RuntimeServiceCoordinatorSelfTest() { }

    public static void main(String[] args) {
        BrokerStateStore state = new BrokerStateStore();
        List<Bundle> calls = new ArrayList<>();
        RuntimeServiceCoordinator coordinator = new RuntimeServiceCoordinator(state, (slot, request) -> {
            calls.add(new Bundle(request));
            Bundle result = new Bundle();
            result.putString(RuntimeKeys.STATUS, "SERVICE_RECOVERED");
            result.putInt("onStartCommandResult", Service.START_REDELIVER_INTENT);
            return result;
        });
        GuestSession stale = session("old", 1);
        Bundle prepared = new Bundle();
        prepared.putString(RuntimeKeys.SESSION_ID, stale.sessionId());
        prepared.putLong(RuntimeKeys.GENERATION, stale.generation());
        state.putPrepared(processKey(stale), prepared);

        Bundle start = request(ComponentOperations.START_FOREGROUND_SERVICE);
        start.putString(ComponentOperations.ACTION, "ACTION_REDELIVER");
        Bundle startResult = success();
        startResult.putInt("onStartCommandResult", Service.START_REDELIVER_INTENT);
        coordinator.applySuccessfulOperation(stale, start, startResult);
        check(startResult.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, false), "foreground start not tracked");

        DeathToken token = new DeathToken();
        Bundle bind = request(ComponentOperations.BIND_SERVICE);
        bind.putString(RuntimeKeys.CONNECTION_ID, "client-1");
        bind.putBinder(RuntimeKeys.SERVICE_CONNECTION_BINDER, token);
        Bundle bindResult = success();
        coordinator.applySuccessfulOperation(stale, bind, bindResult);
        check(bindResult.getBoolean(RuntimeKeys.SERVICE_CONNECTION_DEATH_TRACKED, false),
                "connection death was not tracked");
        check(coordinator.activeConnectionLeases() == 1, "connection lease missing");

        token.die();
        check(coordinator.activeConnectionLeases() == 0, "dead connection lease leaked");
        check(calls.size() == 1
                        && ComponentOperations.UNBIND_SERVICE.equals(
                        calls.get(0).getString(ComponentOperations.OPERATION, "")),
                "connection death did not unbind Guest service");
        ServiceRuntimeRegistry.Snapshot afterDeath = coordinator.snapshot().get(0);
        check(afterDeath.connectionIds().isEmpty() && afterDeath.started(),
                "connection death corrupted started ownership");

        List<ServiceRuntimeRegistry.Snapshot> disconnected = coordinator.disconnectSession(stale);
        check(disconnected.size() == 1 && disconnected.get(0).recoverable(),
                "redeliver service not marked recovering");
        GuestSession current = session("new", 2);
        Bundle newSpec = new Bundle();
        newSpec.putString(RuntimeKeys.SESSION_ID, current.sessionId());
        newSpec.putLong(RuntimeKeys.GENERATION, current.generation());
        try {
            List<ServiceRuntimeRegistry.Snapshot> recovered = coordinator.recoverSession(stale, current, newSpec);
            check(recovered.size() == 1 && recovered.get(0).generation() == 2,
                    "service recovery generation not committed");
        } catch (Exception error) {
            throw new AssertionError("recovery failed", error);
        }
        Bundle recoveryCall = calls.get(calls.size() - 1);
        check(ComponentOperations.START_SERVICE.equals(
                        recoveryCall.getString(ComponentOperations.OPERATION, "")),
                "recovery did not restart service");
        check(recoveryCall.getBoolean(RuntimeKeys.SERVICE_RECOVERY, false)
                        && recoveryCall.getBoolean(RuntimeKeys.SERVICE_REDELIVERED, false),
                "recovery metadata missing");
        check("ACTION_REDELIVER".equals(recoveryCall.getString(ComponentOperations.ACTION, "")),
                "redelivery action missing");
        check(recoveryCall.getInt(RuntimeKeys.SERVICE_START_ID, -1) == 1,
                "recovery start id continuity missing");

        check(coordinator.stopSession(current) == 1 && coordinator.recordCount() == 0,
                "service state leaked after session stop");
        coordinator.close();
        System.out.println("PASS Runtime Service coordinator lifecycle self-test");
    }

    private static GuestSession session(String suffix, long generation) {
        return new GuestSession("session-" + suffix, "com.example", 0, "com.example:remote", 3,
                generation, SessionState.READY, 0, "revision");
    }

    private static String processKey(GuestSession session) {
        return "u" + session.virtualUserId() + ":" + session.packageName()
                + ":" + session.processName();
    }

    private static Bundle request(String operation) {
        Bundle request = new Bundle();
        request.putString(ComponentOperations.OPERATION, operation);
        request.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.SyncService");
        return request;
    }

    private static Bundle success() {
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "OK");
        return result;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class DeathToken extends Binder {
        private IBinder.DeathRecipient recipient;

        @Override public void linkToDeath(IBinder.DeathRecipient value, int flags) throws RemoteException {
            recipient = value;
        }

        @Override public boolean unlinkToDeath(IBinder.DeathRecipient value, int flags) {
            if (recipient != value) return false;
            recipient = null;
            return true;
        }

        void die() {
            IBinder.DeathRecipient value = recipient;
            recipient = null;
            if (value != null) value.binderDied();
        }
    }
}
