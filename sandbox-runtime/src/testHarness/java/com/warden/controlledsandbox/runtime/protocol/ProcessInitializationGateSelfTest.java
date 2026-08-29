package com.warden.controlledsandbox.runtime.protocol;

/** Regression coverage for single-owner process initialization and shared waiters. */
public final class ProcessInitializationGateSelfTest {
    private ProcessInitializationGateSelfTest() { }

    public static void main(String[] args) {
        sharesSameIdentityFuture();
        rejectsConflictingIdentity();
        recordsStructuredFailureAndAllowsRetry();
        rejectsStaleOwnerCompletion();
        System.out.println("PASS process initialization gate self-test");
    }

    private static void sharesSameIdentityFuture() {
        ProcessInitializationGate<String, String> gate = new ProcessInitializationGate<>();
        ProcessInitializationGate<String, String>.Start owner = gate.start("guest-1");
        ProcessInitializationGate<String, String>.Start waiter = gate.start("guest-1");
        require(owner.owner(), "first caller must own initialization");
        require(waiter.waiter(), "same identity must join initialization");
        require(owner.future() == waiter.future(), "waiter must share owner's future");
        require(gate.state() == ProcessInitializationGate.State.INITIALIZING,
                "gate must expose INITIALIZING state");
        gate.completeSuccess(owner, "ready");
        require("ready".equals(waiter.future().join()), "waiter must observe owner result");
        require(gate.state() == ProcessInitializationGate.State.READY,
                "successful owner must publish READY");
    }

    private static void rejectsConflictingIdentity() {
        ProcessInitializationGate<String, String> gate = new ProcessInitializationGate<>();
        ProcessInitializationGate<String, String>.Start owner = gate.start("guest-1");
        ProcessInitializationGate<String, String>.Start conflict = gate.start("guest-2");
        require(conflict.rejected(), "different identity must not race process initialization");
        require(conflict.rejection() != null
                        && "INITIALIZATION_IDENTITY_CONFLICT".equals(
                        conflict.rejection().getMessage()),
                "conflict must retain a deterministic reason");
        gate.completeSuccess(owner, "ready");
    }

    private static void recordsStructuredFailureAndAllowsRetry() {
        ProcessInitializationGate<String, String> gate = new ProcessInitializationGate<>();
        ProcessInitializationGate<String, String>.Start failed = gate.start("guest-1");
        gate.completeFailureResult(failed, "FAILED_BUNDLE",
                new IllegalStateException("guest bootstrap failed"));
        require(gate.state() == ProcessInitializationGate.State.FAILED,
                "structured failure must publish FAILED");
        require("FAILED_BUNDLE".equals(failed.future().join()),
                "structured failure must preserve protocol result");
        require(gate.lastFailure() != null, "failed state must retain failure evidence");

        ProcessInitializationGate<String, String>.Start retry = gate.start("guest-1");
        require(retry.owner(), "a later explicit attempt must own a fresh generation");
        gate.completeSuccess(retry, "ready-again");
        require(gate.state() == ProcessInitializationGate.State.READY,
                "successful retry must recover READY");
    }

    private static void rejectsStaleOwnerCompletion() {
        ProcessInitializationGate<String, String> gate = new ProcessInitializationGate<>();
        ProcessInitializationGate<String, String>.Start owner = gate.start("guest-1");
        gate.completeSuccess(owner, "ready");
        boolean rejected = false;
        try {
            gate.completeSuccess(owner, "stale");
        } catch (IllegalStateException error) {
            rejected = "INITIALIZATION_OWNER_STALE".equals(error.getMessage());
        }
        require(rejected, "stale owner must not complete a later generation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
