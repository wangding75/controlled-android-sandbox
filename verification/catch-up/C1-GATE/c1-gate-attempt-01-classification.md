# C1-GATE attempt 01 classification

- **Result:** failed before the 30-minute pressure window (`elapsed_seconds=57.914`).
- **Command:** `run_c1_t04_rd.py --loops 50 --pressure-seconds 1800` from the current HEAD.
- **Device:** MuMu `RD测试`, API 32, boot ID `7cac15ce-d76e-44ea-968b-959d91d03be7`.
- **Observed marker:** `ActivityTaskManager: Abort background activity starts from 10196`.
- **Debug result:** `LAUNCH_GATE_FAILED: guest Activity create/resume/window not confirmed`.
- **Raw evidence:** `artifacts/capability-audit/catch-up-c1-t04/20260822T040454Z/`.
- **Classification:** `TEST_EVIDENCE_GAP` caused by a harness cold-window precondition; no
  `FATAL EXCEPTION`, ANR, stale-generation, or Provider runtime-failure marker was observed.
- **Repair:** make `force_stop_host=False` honor the no-force-stop contract and warm the
  package-neutral Guest Activity for four seconds before starting the concurrent Provider
  campaign. The runtime is unchanged.
