# C4-R05 Host Phase Boundary Continuation Design (2026-09-01)

## Decision

The 2026-09-01 C4-R05 formal launch matrix reached a real host-side process boundary after
an allowed LOW_MEMORY recovery. The runner returned `124` after its 14,400-second phase
envelope and the child final summary was absent, although durable `case.json` rows existed.
This is neither a formal PASS nor a new Guest readiness result.

The R05 orchestrator therefore gets one explicit, bounded host-phase continuation. It is a
continuation of the durable lane, not a launch retry:

1. Preserve every `case.json` from every prior `attempt-*` directory, including the original
   LOW_MEMORY failure and its post-restart/rebootstrap replacement observation.
2. Re-run the existing ordered-coordinate selector and require an exact first missing
   target/user/iteration/mode.
3. Start one independent child from that coordinate with a new request/operation ID and
   `retryBudget=0`; the child retains the existing 10-second hot and 30-second cold
   FIRST_FRAME_DRAWN deadlines.
4. Allow at most one host-phase continuation. A second host timeout, any non-environment
   terminal failure, missing durable rows, or an incomplete final summary remains fail-closed.
5. Require the final aggregate to contain all expected terminal coordinates. The original
   failure remains in `observations` and is never rewritten as a PASS.

No readiness deadline, launch retry budget, fixed sleep, or package-specific exception is
changed by this design.

## First-failure boundary

The source lane is:

`verification/catch-up/C4-R05/formal-two-round-20260901-rebootstrap-v1/round-1-clean-install-cold/launch-matrix/`

The first actual case failure was Quark user1 cold-006 in `attempt-001`. Its command result
was `RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout`; the preserved
`application-exit-info.txt` identifies host package
`com.warden.controlledsandbox.debug` with `reason=3 (LOW_MEMORY)`. The device snapshot was
also retained and must not be used to convert the command error into a launch success.

The approved dynamic MuMu restart and independent Guest rebootstrap were recorded under
`low-memory-recovery` and `attempt-002/post-restart-rebootstrap`. The continuation produced successful rows through
fanqie user1 hot-015. The host phase then ended at the 14,400-second `run_command` envelope
with no phase summary. The durable selector reports 480 unique completed coordinates and
the exact next coordinate fanqie user1 cold-016; the reconstructed lane contains 481
observations because the original LOW_MEMORY failure is intentionally retained.

## Boundary and reference mapping

The CAS lifecycle boundary is the host-owned process and Guest generation boundary:

- VA reference: `VActivityManagerService.startProcessIfNeedLocked`/
  `processDead`, `ActivityStack.startActivityProcess`/`processDied`, and
  `VirtualRuntime.crash`.
- NBB reference: `BProcessManagerService.startProcessLocked`,
  `ActivityStack.startActivityProcess`, and `BActivityThread.bindApplication`.
- CAS implementation: `run_c4_r03_low_memory_continuation.py` owns the single documented
  host LOW_MEMORY restart/rebootstrap exception; `run_c4_r05_rd.py` owns the formal phase
  envelope and evidence boundary.

The VA/NBB common contract is real process ownership, death, and rebind. A host timeout is
therefore classified at the orchestration boundary and cannot be inferred to be an App,
SX adapter, Window, Surface, or Guest process success. The new lane seed keeps transaction,
Window, Surface, process, request, operation, boot, and screenshot artifacts in their original
case directories.

## Implementation and regression boundary

The implementation is limited to:

- recording `timedOut` and `timeoutSeconds` in every phase command record;
- recognizing a host LOW_MEMORY failure only when the command has the explicit environment
  resolution error and the failure bundle contains host-scoped LOW_MEMORY evidence;
- reconstructing and seeding all durable attempts rather than only the newest child;
- permitting one `HOST_PHASE_BOUNDARY_INTERRUPTION` continuation and requiring the final
  child summary plus full terminal-coordinate aggregate.

Regression coverage includes the existing C4-R03 rebootstrap tests and a pure R05 test that
creates a two-attempt lane, proves the LOW_MEMORY evidence classification, verifies the
original failure and replacement rows are both retained, and checks that continuation selects
the recovered coordinate without treating the old row as a duplicate terminal failure.

The fix does not claim that the original LOW_MEMORY event is harmless for all environments;
it remains a recorded non-blocking exception only under the R05 task-book policy. Any second
LOW_MEMORY event or any non-LOW_MEMORY failure blocks the formal task.
