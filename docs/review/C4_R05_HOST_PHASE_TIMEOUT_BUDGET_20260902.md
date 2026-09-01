# C4-R05 Host Phase Timeout Budget (2026-09-02)

## Decision

C4-R05 formal execution uses a 12-hour host-phase timeout (`43,200` seconds). The same
bound is passed to the nested `run_c4_r03_low_memory_continuation.py` child so the child
cannot terminate at the former 4-hour default while the outer R05 phase is still running.

This is a host orchestration budget, not a readiness deadline. The per-case cold/hot
`FIRST_FRAME_DRAWN` deadlines, first-failure evidence boundary, retry budget, and the
single explicitly permitted host-scoped `LOW_MEMORY` recovery remain unchanged.

## Rationale

The prior formal lanes reached the exact 14,400-second boundary during the serial launch
matrix. The configured workload is two rounds of 25 cold and 25 hot loops for two users and
five targets; the measured serial throughput showed that a complete launch lane can exceed
four hours. A four-hour host envelope therefore interrupted normal durable progress before
the child could write its final summary. The 12-hour value is selected to cover the measured
lane duration with bounded headroom; it is not a readiness or application timeout.

The timeout remains fail-closed: when 43,200 seconds is actually reached, R05 terminates the
complete child process tree, records the command and termination evidence, preserves all
durable rows, and does not convert the lane to PASS. A host continuation is considered only
after that boundary and only under the existing evidence policy.

## Implementation and verification

- `run_c4_r05_rd.py` exposes `--phase-timeout-seconds`, defaulting to `43,200`.
- R04, add-gate, launch matrix, and post-formal regression phases use this value; the clean
  build keeps its separate 3,600-second build bound.
- The launch matrix explicitly passes the same value as
  `--child-timeout-seconds` to its nested continuation runner.
- `scripts/test_c4_r05_orchestrator.py` verifies the default and the host/child propagation.
- `scripts/check-c4-r05-orchestrator.py` checks that the timeout is explicit and auditable.

The next formal run must use a fresh clean commit and retain the previous failed lanes as
historical evidence. No prior attempt is reclassified as PASS by this timeout change.
