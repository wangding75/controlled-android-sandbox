package com.warden.controlledsandbox.contract.internal;

import android.os.IBinder;
import android.os.RemoteException;
import java.util.Objects;

/**
 * Two-phase Binder death link used after an owner has reserved its authoritative record.
 *
 * <p>The owner must insert the record before calling {@link #linkAfterReservation()}, then
 * recheck that the same record is still authoritative before publishing it to callers. A death
 * delivered synchronously from {@code linkToDeath()} therefore observes and removes the reserved
 * record instead of racing with a later insert.</p>
 */
public final class DeathRegistrationHelper implements AutoCloseable {
    private enum State { NEW, LINKING, LINKED, DEAD, CLOSED }

    private final IBinder binder;
    private final Runnable deathAction;
    private final IBinder.DeathRecipient recipient = this::binderDied;
    private State state = State.NEW;
    private boolean linkInstalled;

    public DeathRegistrationHelper(IBinder binder, Runnable deathAction) {
        this.binder = Objects.requireNonNull(binder, "binder");
        this.deathAction = Objects.requireNonNull(deathAction, "deathAction");
    }

    /**
     * Links only after the owner has inserted its reservation.
     *
     * @return {@code true} when the link is active; {@code false} when death or close won the race.
     */
    public boolean linkAfterReservation() throws RemoteException {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException("BINDER_DEATH_LINK_ALREADY_ATTEMPTED");
            }
            state = State.LINKING;
        }

        try {
            binder.linkToDeath(recipient, 0);
        } catch (RemoteException | RuntimeException | Error error) {
            synchronized (this) {
                if (state == State.LINKING) state = State.CLOSED;
            }
            throw error;
        }

        boolean active;
        boolean unlink;
        synchronized (this) {
            linkInstalled = true;
            active = state == State.LINKING && binder.isBinderAlive();
            if (active) {
                state = State.LINKED;
                unlink = false;
            } else {
                if (state == State.LINKING) state = State.CLOSED;
                unlink = true;
            }
        }
        if (unlink) unlinkQuietly();
        return active;
    }

    public synchronized boolean linkedAndAlive() {
        return state == State.LINKED && binder.isBinderAlive();
    }

    @Override public void close() {
        boolean unlink;
        synchronized (this) {
            if (state == State.CLOSED) return;
            unlink = linkInstalled;
            state = State.CLOSED;
        }
        if (unlink) unlinkQuietly();
    }

    private void binderDied() {
        boolean notify;
        synchronized (this) {
            if (state == State.DEAD || state == State.CLOSED) return;
            state = State.DEAD;
            notify = true;
        }
        if (notify) deathAction.run();
    }

    private void unlinkQuietly() {
        try { binder.unlinkToDeath(recipient, 0); }
        catch (RuntimeException ignored) { }
    }
}
