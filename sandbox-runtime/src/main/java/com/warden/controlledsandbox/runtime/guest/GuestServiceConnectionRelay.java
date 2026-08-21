package com.warden.controlledsandbox.runtime.guest;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Build;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Generation-safe adapter from the host AMS ServiceConnection to Guest code.
 *
 * <p>The host callback can arrive after a Guest calls {@code unbindService}, or after the
 * ActivityThread bridge is closing a dead generation.  Android's callback is already Binder
 * ordered, but the caller-supplied Executor is a second asynchronous queue.  This relay keeps
 * that queue ordered and fences every queued callback at the same ownership boundary as the
 * Guest connection itself.  VA/NBB make this distinction in their service callback adapters;
 * leaving it to an arbitrary Executor turns late callbacks into stale-generation reattachment.</p>
 */
final class GuestServiceConnectionRelay implements ServiceConnection {
    private enum Kind { CONNECTED, DISCONNECTED, BINDING_DIED, NULL_BINDING }

    private final ComponentName guestComponent;
    private final ServiceConnection guest;
    private final Executor executor;
    private final Object lock = new Object();
    private final ArrayDeque<Event> pending = new ArrayDeque<>();
    private boolean drainScheduled;
    private boolean closed;
    private boolean connected;
    private boolean terminal;
    private IBinder binder;

    GuestServiceConnectionRelay(ComponentName guestComponent, ServiceConnection guest,
                                Executor executor) {
        this.guestComponent = Objects.requireNonNull(guestComponent, "guestComponent");
        this.guest = Objects.requireNonNull(guest, "guest");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override public void onServiceConnected(ComponentName ignored, IBinder service) {
        if (service == null) {
            onNullBinding(ignored);
            return;
        }
        boolean schedule = false;
        synchronized (lock) {
            if (closed || terminal || (connected && binder == service)) return;
            connected = true;
            binder = service;
            pending.addLast(new Event(Kind.CONNECTED, service));
            if (!drainScheduled) {
                drainScheduled = true;
                schedule = true;
            }
        }
        if (schedule) scheduleDrain();
    }

    @Override public void onServiceDisconnected(ComponentName ignored) {
        boolean schedule = false;
        synchronized (lock) {
            if (closed || terminal || (!connected && binder == null)) return;
            connected = false;
            binder = null;
            pending.addLast(new Event(Kind.DISCONNECTED, null));
            if (!drainScheduled) {
                drainScheduled = true;
                schedule = true;
            }
        }
        if (schedule) scheduleDrain();
    }

    @Override public void onBindingDied(ComponentName ignored) {
        boolean schedule = false;
        synchronized (lock) {
            if (closed || terminal) return;
            terminal = true;
            connected = false;
            binder = null;
            pending.addLast(new Event(Kind.BINDING_DIED, null));
            if (!drainScheduled) {
                drainScheduled = true;
                schedule = true;
            }
        }
        if (schedule) scheduleDrain();
    }

    @Override public void onNullBinding(ComponentName ignored) {
        boolean schedule = false;
        synchronized (lock) {
            if (closed || terminal) return;
            terminal = true;
            connected = false;
            binder = null;
            pending.addLast(new Event(Kind.NULL_BINDING, null));
            if (!drainScheduled) {
                drainScheduled = true;
                schedule = true;
            }
        }
        if (schedule) scheduleDrain();
    }

    /** Fences queued callbacks; one callback already executing may finish normally. */
    void close() {
        synchronized (lock) {
            closed = true;
            connected = false;
            terminal = true;
            binder = null;
            pending.clear();
        }
    }

    private void scheduleDrain() {
        try {
            executor.execute(this::drain);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            synchronized (lock) {
                closed = true;
                pending.clear();
                drainScheduled = false;
            }
            android.util.Log.e("CS_SERVICE_FRAMEWORK", "ServiceConnection callback executor rejected",
                    error);
        }
    }

    private void drain() {
        while (true) {
            Event event;
            synchronized (lock) {
                if (closed) {
                    pending.clear();
                    drainScheduled = false;
                    return;
                }
                event = pending.pollFirst();
                if (event == null) {
                    drainScheduled = false;
                    return;
                }
            }
            try {
                switch (event.kind) {
                    case CONNECTED -> guest.onServiceConnected(guestComponent, event.binder);
                    case DISCONNECTED -> guest.onServiceDisconnected(guestComponent);
                    case BINDING_DIED -> guest.onBindingDied(guestComponent);
                    case NULL_BINDING -> notifyNullBinding();
                }
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                // A user callback must not strand later framework callbacks in this relay.
                android.util.Log.e("CS_SERVICE_FRAMEWORK",
                        "Guest ServiceConnection callback failed kind=" + event.kind, error);
            }
        }
    }

    @SuppressLint("NewApi")
    private void notifyNullBinding() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            guest.onNullBinding(guestComponent);
        }
    }

    private record Event(Kind kind, IBinder binder) { }
}
