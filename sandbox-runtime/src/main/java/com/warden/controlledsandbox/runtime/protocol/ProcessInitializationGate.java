package com.warden.controlledsandbox.runtime.protocol;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Converges process-local initialization into one in-flight operation per identity.
 *
 * <p>The owner performs the expensive initialization, while concurrent callers for the same
 * identity receive the owner's future.  A different identity is rejected instead of allowing
 * two process-wide initializers to race.  A failed operation leaves the gate in {@link State#FAILED}
 * but a later explicit attempt may start a fresh generation.</p>
 */
public final class ProcessInitializationGate<K, V> {
    public enum State { UNINITIALIZED, INITIALIZING, READY, FAILED }

    public enum Decision { OWNER, WAITER, REJECTED }

    /** A stable decision returned by {@link #start(Object)}. */
    public final class Start {
        private final Decision decision;
        private final K key;
        private final CompletableFuture<V> future;
        private final Throwable rejection;
        private final Flight flight;

        private Start(Decision decision, K key, CompletableFuture<V> future,
                      Throwable rejection, Flight flight) {
            this.decision = decision;
            this.key = key;
            this.future = future;
            this.rejection = rejection;
            this.flight = flight;
        }

        public Decision decision() { return decision; }
        public K key() { return key; }
        public CompletableFuture<V> future() { return future; }
        public Throwable rejection() { return rejection; }
        public boolean owner() { return decision == Decision.OWNER; }
        public boolean waiter() { return decision == Decision.WAITER; }
        public boolean rejected() { return decision == Decision.REJECTED; }
    }

    private final Object lock = new Object();
    private Flight flight;
    private State state = State.UNINITIALIZED;
    private Throwable lastFailure;

    /**
     * Claims the current initialization generation or joins it.
     *
     * <p>Only one owner can be returned.  Callers must complete an owner with either
     * {@link #completeSuccess(Start, Object)} or {@link #completeFailure(Start, Throwable)}.</p>
     */
    public Start start(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            if (flight != null) {
                if (!flight.key.equals(key)) {
                    return new Start(Decision.REJECTED, key, flight.future,
                            new IllegalStateException("INITIALIZATION_IDENTITY_CONFLICT"), flight);
                }
                return new Start(Decision.WAITER, key, flight.future, null, flight);
            }
            flight = new Flight(key);
            state = State.INITIALIZING;
            lastFailure = null;
            return new Start(Decision.OWNER, key, flight.future, null, flight);
        }
    }

    /** Returns whether another caller currently owns initialization. */
    public boolean initializing() {
        synchronized (lock) { return flight != null; }
    }

    public State state() {
        synchronized (lock) { return state; }
    }

    public Throwable lastFailure() {
        synchronized (lock) { return lastFailure; }
    }

    /** Publishes a successful result and wakes every waiter. */
    public void completeSuccess(Start owner, V value) {
        Objects.requireNonNull(owner, "owner");
        Flight completed;
        synchronized (lock) {
            completed = requireOwnerLocked(owner);
            flight = null;
            state = State.READY;
            lastFailure = null;
        }
        completed.future.complete(value);
    }

    /** Publishes a failed result and wakes every waiter. */
    public void completeFailure(Start owner, Throwable error) {
        Objects.requireNonNull(owner, "owner");
        Throwable failure = error == null
                ? new IllegalStateException("INITIALIZATION_FAILED") : error;
        Flight completed;
        synchronized (lock) {
            completed = requireOwnerLocked(owner);
            flight = null;
            state = State.FAILED;
            lastFailure = failure;
        }
        completed.future.completeExceptionally(failure);
    }

    /**
     * Publishes a protocol-level failure result without turning it into a transport exception.
     * Some Binder protocols deliberately return a FAILED bundle so every caller observes the same
     * structured error while the gate still records the FAILED state.
     */
    public void completeFailureResult(Start owner, V value, Throwable error) {
        Objects.requireNonNull(owner, "owner");
        Throwable failure = error == null
                ? new IllegalStateException("INITIALIZATION_FAILED") : error;
        Flight completed;
        synchronized (lock) {
            completed = requireOwnerLocked(owner);
            flight = null;
            state = State.FAILED;
            lastFailure = failure;
        }
        completed.future.complete(value);
    }

    /** Resets a quiescent process after its initialized resource is explicitly shut down. */
    public void reset() {
        synchronized (lock) {
            if (flight != null) throw new IllegalStateException("INITIALIZATION_IN_PROGRESS");
            state = State.UNINITIALIZED;
            lastFailure = null;
        }
    }

    private Flight requireOwnerLocked(Start owner) {
        if (owner.decision != Decision.OWNER || flight == null || owner.flight != flight) {
            throw new IllegalStateException("INITIALIZATION_OWNER_STALE");
        }
        return flight;
    }

    private final class Flight {
        final K key;
        final CompletableFuture<V> future = new CompletableFuture<>();

        Flight(K key) { this.key = key; }
    }
}
