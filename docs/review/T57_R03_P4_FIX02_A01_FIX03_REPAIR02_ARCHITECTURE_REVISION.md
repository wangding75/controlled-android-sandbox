# T57-R03-P4-FIX02-A01-FIX03-REPAIR02 — Architecture Revision AR-02

`ARCHITECTURE_REVISION: AR-02`

This document supersedes the FIX01 statement that the bounded Host Activity
family is `64 × 2 = 128` components. The current implementation requires a
bounded physical identity window pool per ordinary process slot so that live
ATMS `ActivityRecord` identities remain distinct while virtual Activity tokens
are reused or reordered.

## Frozen architecture after AR-02

| Dimension | Frozen value | Contract |
| --- | ---: | --- |
| ordinary process slots | 64 | Host process-slot boundary |
| isolated process slots | 16 | Isolated-slot boundary; no Activities declared |
| physical window pool per ordinary slot | 16 | bounded live physical Activity identities |
| Host Activity components | `64 × 2 × 16 = 2048` | opaque/translucent family × pool |
| Host activity aliases | 0 | no alias pool |

The former 128-component figure is a superseded FIX01 architecture claim; it
must not be reported as the current frozen architecture. Launch-mode, task,
route, and virtual Activity semantics remain in the existing runtime/ledger
path. AR-02 changes the physical identity capacity and its documentation, not
the Activity mapping decision logic.

## Exhaustion and evidence contract

`PhysicalActivityIdentityAllocator` is bounded and does not modulo-wrap. The
17th simultaneously live identity must fail closed with
`PHYSICAL_ACTIVITY_IDENTITY_POOL_EXHAUSTED`; release and rebind must not create
a collision. `PhysicalActivityIdentityAllocatorSelfTest` is a required static
gate and must print `PASS bounded physical Activity identity allocator`.

The activity semantic runner proves task behavior from fixture lifecycle
observations plus runtime `ATMS_ACTIVITY_LAUNCH_REQUEST`,
`ATMS_ACTIVITY_RECORD_MAPPING`, guest lifecycle events, and before/transition/
after `dumpsys activity activities` snapshots. Fixture output contains no
semantic pass booleans. The runner must fail closed for missing mappings,
missing dumpsys evidence, missing lifecycle transitions, or missing required
API evidence.

## PackageParser/API verification

The 2048-component / 0-alias manifest census and PackageParser/install checks
are required on API 32, API 35, and API 36. The final A01 matrix is valid only
when one clean, same-source-commit device evidence file for each required API
has `overall_pass=true` and a valid canonical evidence SHA-256.

`FROZEN_STUB_ARCHITECTURE=PASS` is invalid without this AR-02 revision. No A02
work is included in this repair.
