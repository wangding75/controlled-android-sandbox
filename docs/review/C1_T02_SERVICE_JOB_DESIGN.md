# C1-T02 Service/FGS/Job device campaign design

## Scope

`C1-T02` closes the package-neutral Service, foreground-service, and Job lifecycle evidence
surface on MuMu `RD测试` API 32. The campaign is deliberately separate from the C1-T01
Activity/Task matrix and uses the existing `FixtureService` and `FixtureJobService` rather than a
product package or package-name branch.

## DISCOVER / CLASSIFY

The source review found the following owners already wired:

| Surface | Owner | Existing evidence | Remaining C1-T02 proof |
|---|---|---|---|
| started Service | `RuntimeServiceCoordinator` + `ServiceRuntimeRegistry` | start-id, sticky/redelivery, generation recovery self-tests | repeated real Guest start/stop and stale start-id observation |
| bound Service | `RuntimeServiceCoordinator` + `BoundServiceLease` | Binder-death and connection ledger self-tests | two live bindings, callback-side bind/unbind and cleanup on the real Guest path |
| foreground Service | `GuestActivityThreadServiceLifecycle` + foreground state machine | RD-10 transport and source checks | repeated promotion/demotion and state convergence |
| JobService | `VirtualJobService` + `GuestJobServiceBridge` | Job callback bridge and RD-11 work-item probe | repeated schedule/dispatch/finish and stale callback cleanup |
| death/recovery | `RuntimeComponentRecoveryCoordinator` | RD-07 sticky foreground Service recovery | service state and generation fence under campaign pressure |

The initial governance run also found two non-runtime findings. The SBOM was stale after the
previous C1-T01 fixture changes and is regenerated as part of the acceptance preflight. The
M4-T12 checker still required pre-split `RuntimeBrokerService` call-site strings even though the
current owners are `RuntimeComponentRecoveryCoordinator` and
`RuntimeProviderResourceCoordinator`; the checker is updated to assert those current owners.
Neither finding is treated as a runtime PASS.

## Design

The debug-only `service-lifecycle-suite` command executes one generation-fenced cycle in a single
`RuntimeClient` session:

1. prepare the Guest and deliver two started-Service instances;
2. stop the stale first start ID and assert the newer start remains owned;
3. create two Binder-backed `BoundServiceLease` bindings and close both leases;
4. request foreground start, promote, demote, and assert the virtual foreground state;
5. stop the Service and assert that started, bound, and foreground ownership converges to zero.

`run_c1_t02_rd.py` runs the same cycle for virtual users 0 and 1, records every command result and
the raw logcat, and fails closed on any missing marker, stale generation, fatal exception, ANR,
non-monotonic start ID, leaked connection, or non-convergent Service state. Existing RD-10,
RD-11, and RD-07 probes remain mandatory companion gates for framework FGS transport, real
JobWorkItem dequeue/complete, and process-death recovery. All device selection is resolved from
the MuMu instance name at runtime.

## Acceptance override

Per the 2026-08-21 execution instruction, C1-T02 requires a 30-minute short pressure test across
both virtual users and does not require the previously listed 8-hour soak. The deferred follow-up
issue is recorded as `C1-T02-ISSUE-8H-STABILITY-SOAK`: run the extended soak with leak/ghost-task
and ANR telemetry before any higher-maturity claim. This issue does not block the current
`RD_BASELINE` DONE decision.

## Exit evidence

The tracked receipt records commit, APK hashes, device snapshot, source/static gates, per-user
cycle counts, framework companion probes, raw evidence paths, and any unproven API/OEM/long-soak
dimensions. `RD_BASELINE` is not generalized to Android Matrix, OEM, or VA PRO equivalence.
