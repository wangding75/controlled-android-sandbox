package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Executable regression for ordered, generation-fenced ServiceConnection callbacks. */
public final class GuestServiceConnectionRelaySelfTest {
    private GuestServiceConnectionRelaySelfTest() { }

    public static void main(String[] args) {
        testCallbackOrderAndTerminalFence();
        testCloseDropsQueuedCallbacks();
        System.out.println("PASS Guest ServiceConnection relay self-test");
    }

    private static void testCallbackOrderAndTerminalFence() {
        QueueExecutor executor = new QueueExecutor();
        List<String> events = new ArrayList<>();
        ComponentName component = new ComponentName("com.example.guest", "GuestService");
        IBinder first = new Binder();
        ServiceConnection guest = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                require(component.flattenToShortString().equals(name.flattenToShortString()),
                        "relay must expose Guest component identity");
                events.add("connected:" + (binder == first));
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                events.add("disconnected");
            }
            @Override public void onBindingDied(ComponentName name) {
                events.add("binding-died");
            }
            @Override public void onNullBinding(ComponentName name) {
                events.add("null-binding");
            }
        };
        GuestServiceConnectionRelay relay = new GuestServiceConnectionRelay(component, guest, executor);
        relay.onServiceConnected(new ComponentName("host", "StubService"), first);
        relay.onServiceDisconnected(new ComponentName("host", "StubService"));
        relay.onServiceConnected(new ComponentName("host", "StubService"), new Binder());
        relay.onBindingDied(new ComponentName("host", "StubService"));
        relay.onServiceConnected(new ComponentName("host", "StubService"), new Binder());
        executor.runAll();
        require(events.equals(List.of("connected:true", "disconnected", "connected:false", "binding-died")),
                "ServiceConnection callback order/terminal fence mismatch: " + events);
        relay.onNullBinding(new ComponentName("host", "StubService"));
        executor.runAll();
        require(events.size() == 4, "terminal binding-death relay accepted a later callback");
    }

    private static void testCloseDropsQueuedCallbacks() {
        QueueExecutor executor = new QueueExecutor();
        int[] callbacks = {0};
        ServiceConnection guest = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { callbacks[0]++; }
            @Override public void onServiceDisconnected(ComponentName name) { callbacks[0]++; }
        };
        GuestServiceConnectionRelay relay = new GuestServiceConnectionRelay(
                new ComponentName("com.example.guest", "GuestService"), guest, executor);
        relay.onServiceConnected(new ComponentName("host", "StubService"), new Binder());
        relay.close();
        executor.runAll();
        require(callbacks[0] == 0, "close must fence a queued onServiceConnected callback");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable command) { queue.addLast(command); }
        synchronized void runAll() {
            while (!queue.isEmpty()) queue.removeFirst().run();
        }
    }
}
