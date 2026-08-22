# C1-GATE provider attempt 02 classification

- **Result:** failed before the 30-minute pressure window (`elapsed_seconds=52.392`).
- **Command:** `t57_rd_c1_stage_gate.ps1` provider leg, which invokes
  `run_c1_t04_rd.py --loops 50 --pressure-seconds 1800` from the current HEAD.
- **Device:** MuMu `RD测试`, API 32, boot ID `7cac15ce-d76e-44ea-968b-959d91d03be7`.
- **Observed sequence:** both virtual users completed the first Provider generation and emitted
  `C1_T04_PROVIDER_PASS`; after both Guest windows were reclaimed, the next generation was
  rejected by Android API32 with `Abort background activity starts from 10196` and
  `LAUNCH_GATE_FAILED`.
- **Raw evidence:** `artifacts/capability-audit/catch-up-c1-t04/20260822T050038Z/`.
- **Classification:** `TEST_EVIDENCE_GAP`; no Provider assertion, fatal, ANR, or resource
  convergence failure was observed. A short two-user, two-loop rerun passed immediately, so
  this was a repeatability defect in the campaign's foreground-window precondition.
- **Repair applied:** successful `ProviderCampaignActivity` generations now remain visible
  until the Host-owned `RuntimeClient.stop()` fence. The concurrent Host campaign is being
  run with generation-synchronized teardown: it fences and relaunches
  user0 while user1 remains visible, then fences and relaunches user1 while user0 remains visible.
  Failure paths still finish immediately and the Host still owns cleanup.
- **Repair verification:** `provider-diagnostic-loops-2.json` and
  `provider-diagnostic-loops-2.json` and `provider-diagnostic-loops-5.json` both passed with the
  repaired APK set; the final current-head receipt
  `c1-gate-provider-rd-summary.json` completed 105 cycles per virtual user and 1817.687 seconds
  without a background-start rejection.
