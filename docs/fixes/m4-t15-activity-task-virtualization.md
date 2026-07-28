# M4-T15 Activity and Task virtualization hardening

## Status

**SOURCE/HOST PASS. DEVICE NOT TESTED.**

M4-T15 starts from commit `68a93bc9983d3a8fe8929ce992d4f56649a8af19` and strengthens the existing Activity task ledger. It does not claim complete Android ActivityTaskManager parity.

## Implemented scope

### Launch and result semantics

- Added bounded handling for `FLAG_ACTIVITY_NO_HISTORY`, `FLAG_ACTIVITY_FORWARD_RESULT`, `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`, `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`, `FLAG_ACTIVITY_RETAIN_IN_RECENTS` and `FLAG_ACTIVITY_NEW_DOCUMENT`.
- `FORWARD_RESULT` transfers the caller's pending result ownership to the newly launched Activity and rejects invalid combinations.
- `NO_HISTORY` removes an Activity after it is left, without retaining a resumable back-stack entry.
- Existing standard, singleTop, singleTask and singleInstance source models continue to share one task ledger.

### Running and recent task policy

- Added package/user-isolated running-task and recent-task projections.
- Added owner-checked move-to-front and remove-task operations.
- Added deterministic activation ordering and a bounded recent-task history.
- Excluded tasks do not enter recents unless the retain-in-recents policy applies to the document task.

### Broker restart restoration

- Added a versioned `ActivityTaskCheckpoint` model.
- Added an atomic, CRC32-protected checkpoint store with a 4 MiB maximum input bound.
- Corrupt or unsupported checkpoints fail closed and may be quarantined as `.corrupt` evidence.
- Restored Activities keep bounded saved state, component identity and task order.
- Dead Binder/route authority, one-time launch tokens and pending result transport are deliberately not restored.
- The first valid launch after restoration may adopt the restored task into the current Guest process generation.

### Runtime contract

- Added typed `ActivityTaskRequest`, `ActivityTaskResult` and `ActivityTaskSnapshot` Parcelable contracts.
- Added `IRuntimeBroker.activityTaskOperation(ActivityTaskRequest request)`.
- Supported operations:
  - query running tasks;
  - query recent tasks;
  - move an owned task to front;
  - remove an owned task;
  - query restoration/checkpoint status.
- Every operation is resolved through an active `GuestSession` and checked against package, virtual user and generation ownership.

## Failure boundaries

- Oversized, truncated, CRC-invalid or structurally invalid checkpoint files are rejected.
- Cross-package and cross-virtual-user task access is rejected.
- Stale Guest generations cannot mutate current task state.
- Route-bound deliveries are transient and fail closed across Runtime Broker restart.
- Task and activity counts are bounded during checkpoint decode.

## Local evidence

The following host/source checks pass:

- `scripts/check-activity-task-virtualization.py`
- `scripts/check-m3-source-progress.py`
- `scripts/check-service-lifecycle.py`
- `tools/static_android_compile.py`
- Activity ledger result forwarding, no-history, recent/running task, move/remove and checkpoint restoration tests.
- Checkpoint persistence round-trip, corruption rejection and quarantine tests.
- Broker restart restoration and restored-generation adoption tests.

## Known limitations

1. No production adapter yet exposes complete Android `ActivityManager.RunningTaskInfo`, `RecentTaskInfo` or `AppTask` behavior to Guest framework calls.
2. Runtime Binder task operations are internal typed-envelope operations; Guest-side framework interception is not complete.
3. Window, transition, process recreation and system Recents UI behavior are not device-verified.
4. Android version/OEM-specific `Intent` flag combinations remain incomplete.
5. Checkpoint persistence occurs after accepted in-memory mutations; storage failure is reported but does not roll back the mutation transaction.
6. `ActivityTaskLedger` is now a large class and should be decomposed before adding a broader Android signature matrix.
