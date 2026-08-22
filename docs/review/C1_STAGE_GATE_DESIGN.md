# C1 Stage Gate Design

Task: `C1-GATE` (phase gate for the CAS VA PRO catch-up plan)

## Scope

This gate closes only the C1 phase gate in section 5.3 of
`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md`. It does not start
`C2-T01` and it does not make an Android-matrix, OEM, commercial-app, VA PRO-equivalent,
or hostile-native claim.

The phase gate requires:

1. C1-T01 through C1-T07 accepted on MuMu `RD测试` for both virtual users;
2. the retained 50-loop evidence for the component campaigns;
3. a current-HEAD 30-minute Provider pressure run; and
4. current-HEAD lifecycle/recovery evidence showing convergence after clear, delete,
   restart, process death, and cross-ABI teardown.

## DISCOVER / CLASSIFY result

The seven task receipts are already `DONE`. The phase remained `PENDING` because the
C1-T04 receipt explicitly recorded that its 1800-second pressure window was not awaited,
and C1-T07 recorded the C1-wide dual-user/pressure gate as a follow-up. This is classified
as `TEST_EVIDENCE_GAP`; no new runtime issue is opened before the supplementary campaign.

## Design and execution order

1. Build the device-test APK set from the current Git HEAD and record the four artifact
   hashes.
2. Resolve MuMu `RD测试` by instance name. The gate runner passes the resolved serial
   only as a value returned by the resolver; no ADB endpoint is embedded in the runner.
3. Run `run_c1_t04_rd.py --loops 50 --pressure-seconds 1800` with streamed raw logcat.
   Successful Provider windows remain Host-stop-owned during the concurrent campaign, and
   generation teardown is synchronized so API32 background-start policy does not turn cleanup
   timing into a false campaign failure. The package catalog also preserves install-time
   ordering when the device wall clock moves backwards across a reboot or snapshot restore.
4. Run `t57_rd_full_regression.ps1`, which covers framework transport, Job/FGS,
   ordinary recovery, isolated service, lifecycle clear/delete/reinstall, and cross-ABI
   recovery/lifecycle. Each probe resolves the named instance again.
5. Run `validate_c1_stage_gate.py`. It fails closed unless the historical C1 receipts,
   the current pressure receipt, the current full-regression transcript, device identity,
   and resource-convergence result files all agree.

## Exit boundary

Only a validator result of `PASS` may update the phase to `DONE` and point the ledger at
`C2-T01`. A failure remains `BLOCKED` with its recovery condition. The resulting receipt
is `RD_BASELINE` for the resolved API 32 device only.
