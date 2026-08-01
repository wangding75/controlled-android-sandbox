package com.warden.controlledsandbox.runtime.broker;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import com.warden.controlledsandbox.contract.IGuestProcess;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import java.util.ArrayList;
import java.util.List;

/** Direct executable ownership regression for RuntimeGuestConnectionPool. */
public final class RuntimeGuestConnectionPoolSelfTest {
    private RuntimeGuestConnectionPoolSelfTest() { }

    public static void main(String[] args) throws Exception {
        TestService service = new TestService();
        List<String> disconnects = new ArrayList<>();
        RuntimeGuestConnectionPool pool = new RuntimeGuestConnectionPool(
                service, (slot, reason) -> disconnects.add(slot + ":" + reason));

        Bundle first = pool.call(2, guest -> {
            require(guest == service.guest, "pool did not publish the bound Guest capability");
            Bundle value = new Bundle();
            value.putString("result", "first");
            return value;
        });
        require("first".equals(first.getString("result")), "Guest call result mismatch");
        require(service.bindCount == 1, "initial call did not bind exactly once");

        service.guest.die();
        require(disconnects.equals(List.of("2:BINDER_DIED")),
                "Binder death was not reported through the pool owner");
        require(service.unbindCount == 1, "Binder death did not release the binding");

        service.guest = new FakeGuest();
        Bundle rebound = pool.call(2, guest -> {
            require(guest == service.guest, "pool reused the dead Guest capability");
            Bundle value = new Bundle();
            value.putString("result", "rebound");
            return value;
        });
        require("rebound".equals(rebound.getString("result")), "rebound call failed");
        require(service.bindCount == 2, "dead connection did not trigger a new bind");

        pool.release(2);
        require(service.unbindCount == 2, "release did not unbind the live connection");
        pool.close();
        require(service.unbindCount == 2, "close repeated an already-owned unbind");
        System.out.println("PASS RuntimeGuestConnectionPool direct ownership self-test");
    }

    private static final class TestService extends Service {
        private FakeGuest guest = new FakeGuest();
        private int bindCount;
        private int unbindCount;

        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            bindCount++;
            connection.onServiceConnected(new ComponentName(
                    "com.warden.controlledsandbox", "Guest" + bindCount), guest);
            return true;
        }

        @Override public void unbindService(ServiceConnection connection) {
            unbindCount++;
        }
    }

    private static final class FakeGuest extends IGuestProcess.Stub {
        private IBinder.DeathRecipient recipient;
        private boolean alive = true;

        @Override public RuntimeOperationResult executeV2(RuntimeOperationRequest request) {
            return null;
        }

        @Override public void shutdown(String sessionId, long generation) { }

        @Override public boolean isBinderAlive() { return alive; }

        @Override public void linkToDeath(IBinder.DeathRecipient value, int flags)
                throws RemoteException {
            if (!alive) throw new RemoteException("dead");
            recipient = value;
        }

        @Override public boolean unlinkToDeath(IBinder.DeathRecipient value, int flags) {
            if (recipient != value) return false;
            recipient = null;
            return true;
        }

        void die() {
            alive = false;
            IBinder.DeathRecipient value = recipient;
            if (value != null) value.binderDied();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
