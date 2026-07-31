package com.warden.controlledsandbox.runtime.protocol;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.List;

public final class RebindableServiceConnectorSelfTest {
    private RebindableServiceConnectorSelfTest() { }

    public static void main(String[] args) throws Exception {
        reconnectsAfterBinderDeath();
        backsOffAfterRejectedBinding();
        closesAdaptedCapabilities();
        System.out.println("PASS rebindable service connector self-test");
    }

    private static void reconnectsAfterBinderDeath() throws Exception {
        FakeContext context = new FakeContext();
        List<String> closed = new ArrayList<>();
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), binder -> "service-" + context.bindCount,
                closed::add, "test service", 1_000L, 0L, 0L);

        FakeBinder first = new FakeBinder();
        context.nextBinder = first;
        require("service-1".equals(connector.require()), "first Binder connection must resolve");
        first.die();
        require(!connector.snapshot().connected(), "Binder death must invalidate cached service");

        context.nextBinder = new FakeBinder();
        require("service-2".equals(connector.require()), "next request must rebind after death");
        require(context.bindCount == 2, "death recovery must create exactly one new binding");
        require(closed.equals(List.of("service-1")), "dead adapted capability must be closed");
        connector.close();
    }

    private static void backsOffAfterRejectedBinding() throws Exception {
        FakeContext context = new FakeContext();
        context.rejectCount = 1;
        context.nextBinder = new FakeBinder();
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), ignored -> "ready", ignored -> { },
                "retry service", 1_000L, 1L, 4L);
        require("ready".equals(connector.require()), "connector must retry a rejected first binding");
        require(context.bindCount == 2, "rejected binding must be retried");
        require(connector.snapshot().consecutiveFailures() == 0,
                "successful reconnect must clear failure count");
        connector.close();
    }

    private static void closesAdaptedCapabilities() throws Exception {
        FakeContext context = new FakeContext();
        context.nextBinder = new FakeBinder();
        List<String> closed = new ArrayList<>();
        RebindableServiceConnector<String> connector = new RebindableServiceConnector<>(
                context, new Intent(), ignored -> "session", closed::add,
                "close service", 1_000L, 0L, 0L);
        connector.require();
        connector.close();
        require(closed.equals(List.of("session")), "close must release adapted session");
        require(context.unbindCount == 1, "close must unbind active connection");
    }

    private static final class FakeContext extends Context {
        int bindCount;
        int unbindCount;
        int rejectCount;
        FakeBinder nextBinder;

        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            bindCount++;
            if (rejectCount > 0) {
                rejectCount--;
                return false;
            }
            FakeBinder binder = nextBinder == null ? new FakeBinder() : nextBinder;
            nextBinder = null;
            connection.onServiceConnected(null, binder);
            return true;
        }

        @Override public void unbindService(ServiceConnection connection) { unbindCount++; }
    }

    private static final class FakeBinder implements IBinder {
        private DeathRecipient recipient;
        private boolean alive = true;

        @Override public boolean isBinderAlive() { return alive; }
        @Override public void linkToDeath(DeathRecipient value, int flags) throws RemoteException {
            recipient = value;
        }
        @Override public boolean unlinkToDeath(DeathRecipient value, int flags) {
            if (recipient == value) recipient = null;
            return true;
        }
        void die() {
            alive = false;
            DeathRecipient callback = recipient;
            if (callback != null) callback.binderDied();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
