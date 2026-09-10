# P1-16 reference decision — emulator gate and clean warm-launch baseline

Date: 2026-09-09. Recorded before the P1-16 harness correction.

## Reference execution paths examined

| Reference | Observed execution method | CAS gate implication |
| --- | --- | --- |
| NewBlackbox | `BActivityThread.handleBindApplication` obtains the virtual package information, creates the package context/LoadedApk, configures runtime/native I/O, then makes the Application. | A cold-start check must not pair a new controller/broker state with a previous physical Guest Activity that belongs to an earlier process/task lifetime. |
| NewBlackbox | `IActivityManagerProxy.GetContentProvider` resolves a virtual provider and process only after the virtual owner is known; unresolved state is returned/handled explicitly rather than silently reused. | Do not manufacture task reuse from a stale physical record after the virtual ledger has been reset. Preserve the first receipt and establish a clean precondition instead. |
| VirtualApp OSS | `VClientImpl` creates the Application, installs providers, calls `onCreate`, then marks execution complete. | Application/process lifecycle is a real boundary; a test harness must not call its next launch "warm" if it intentionally retired the control/runtime owner but leaves an old Guest process alive. |
| VirtualApp OSS | `MethodProxies.BindService` / `StartService` resolve a virtual component when present and otherwise delegate. | Reconciliation may use only an active, resolved virtual record. A test-only cleanup must not add a fallback, widen routing, or revive a missing record. |

## Reproduced first failure and decision

`out/verification/p1-16-api36-core-20260909/cases/S04-warm-launch-reuse/` retains the
first S04 failure as `ACTIVITY_REUSE_NOT_OBSERVED`. Its log shows an earlier physical Guest
Activity still alive after the S03 Host fence, while the newly started Broker created a new
virtual Activity (`CREATED_ACTIVITY`) and did not emit `LAUNCHER_TASK_REUSE_SELECTED`.
This is a contaminated harness baseline, not evidence that standard Android launch mode should
always receive `onNewIntent`, and not grounds to modify the ledger or runtime task policy.

The minimal repair is in `tools/verification/capabilities/smoke.py` only: when a case explicitly
requests a cold Host fence, force-stop its named Guest fixture as well and wait for that package to
stop before dispatch. The S04 warm case remains `force_stop_host=False`, so it must still obtain a
real launcher task-reuse / `NEW_INTENT` witness from the immediately preceding S03 session.

## Rejected changes

* changing the Guest manifest launch mode, making every standard Activity `singleTop`, or
  synthesizing a `NEW_INTENT` marker;
* restoring a ledger record from an unverified physical Activity, retaining arbitrary old tasks,
  or relaxing `GuestLaunchObservation` correlation;
* extending timeouts, adding retries, changing the debug command's authority, or changing CAS
  runtime/ABI/native policy.
