# C4-R05 recovery prewarm rollback fix — 2026-08-31

## First-failure signature

The formal clean-install/cold launch matrix passed 162 persisted cases. On continuation,
`dingtalk/user1/cold-007` passed, while the immediately following `hot-007` failed with
`SESSION_BUSY:PREPARING`:

- request: `9746f9500c97412caee29f44eebf9896`
- operation: `9746f9500c97412caee29f44eebf9896-launch`
- attempt: `2` (manual continuation lane), retry budget `0`
- automatic retry: `false`; retryable: `false`
- evidence: `verification/catch-up/C4-R05/formal-two-round-20260831-publication-race-fix/round-1-clean-install-cold/launch-matrix/attempt-002/attempts/dingtalk/user-1/hot-007/`

The same evidence contains `GUEST_RECOVERY_PREWARM_COMPLETED status=FAILED` and the Guest
callback error `PREPARED_SPEC_MISSING`. The hot launch snapshot proves the command failed
before readiness; its non-black screenshot is not used to override the command result.

## Root cause and boundary

`GuestRecoveryPrewarmCoordinator` schedules recovery after a process disconnect. Its call to
`RuntimeGuestLifecycleCoordinator.prepareGuest` can reach a Guest callback failure after the
broker has allocated the new generation and moved it to `PREPARING`. The outer catch in
`prepareGuest` returned a failure bundle but did not roll back that lease. The next explicit
launch therefore observed the stale `PREPARING` state and correctly returned `SESSION_BUSY`.

This is a CAS lifecycle/transaction defect. It is not an SX adapter or app-specific visual
failure, and no retry, sleep, deadline extension, or static marker is involved.

## VA/NBB comparison

The VA/NBB recovery contract terminates a failed prepare generation and revokes its process,
window and component ownership before another operation can observe the package/user/process
as reusable. The CAS path already had the equivalent cleanup around the direct Guest call and
the explicit Guest `FAILED` result, but missed exceptions returned by the outer lifecycle path.

The adopted fix is the smallest contract-preserving completion: on an outer prepare exception,
`ALLOCATED` or `PREPARING` is transitioned to `FAILED`; the process slot and prepared spec are
released; system-service, Activity, Service, Receiver, Provider and cross-ABI ownership is
invalidated; and `GUEST_PREPARE_ROLLBACK` is emitted. No automatic retry is added. The existing
R02 single-flight/BUSY semantics and R03 FIRST_FRAME_DRAWN gate remain unchanged.

## Regression and acceptance

Static Android compilation, `:sandbox-domain:test`, `:sandbox-runtime:test`, and
`git diff --check` pass after the first rollback change. A targeted cold-to-hot run then showed
that Guest prepare can return a structured `FAILED` Bundle rather than throw; the prewarm
coordinator now closes `ALLOCATED/PREPARING` in that path too and removes the prepared spec.
The formal R05 matrix remains `IN_PROGRESS` and must resume from the recorded failure coordinate
on a newly built clean commit. KI-R03-064 remains open and blocking until both formal rounds,
the regressions, commercial add gate, and dual-user short test pass.
