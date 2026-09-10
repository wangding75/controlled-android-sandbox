# P1-16 P1-GATE receipt and Xiaomi handoff

**Status:** `P1_GATE_PASS` — 2026-09-09. P1-01 through P1-16 now have a closed AVD gate. This is a handoff gate, not Xiaomi/ARM64 validation.

## Reference decision and the only correction

Before the correction, `P1-16_REFERENCE_DECISION.md` compared NBB
`BActivityThread.handleBindApplication` and `IActivityManagerProxy.GetContentProvider` with VA
`VClientImpl` and `MethodProxies.BindService`/`StartService`. Those paths establish a real
Application/process and virtual-owner boundary; they do not permit reusing an unknown physical
Activity after the Broker/ledger is restarted.

The first API36 run, `out/verification/p1-16-api36-core-20260909`, recorded one S04
`ACTIVITY_REUSE_NOT_OBSERVED` failure. Its log showed a stale independently installed Guest
Activity surviving the Host force-stop while the new Broker had no matching ledger. The failure
was retained as evidence. The smallest correction was verification-only:
`tools/verification/capabilities/smoke.py` now cold-fences the named Guest fixture together with
the Host and waits for its process to stop. S04 deliberately skips that Guest fence and verifies
real task reuse. CAS runtime, task ledger policy, permissions, timeouts, retries, and ABI/native
policy were not changed.

## Final core gate

| Lane | Device coordinate | S01–S10 | Key evidence |
| --- | --- | --- | --- |
| API35 | Google `sdk_gphone64_x86_64`, API35, x86_64, actual 4096-byte page, fingerprint `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` | **10/10 PASS** | `out/verification/p1-16-api35-final-gate-20260909/summary.md` and `run.json` |
| API36 | Google `sdk_gphone64_x86_64`, API36, x86_64, actual 4096-byte page, fingerprint `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` | **10/10 PASS** | `out/verification/p1-16-api36-final-gate-20260909/summary.md` and `run.json` |

The final API36 S04 timeline contains `PRE_REUSE_*`, `LIFECYCLE_NEW_INTENT`,
`ACTIVITY_RESUMED`, and `FIRST_FRAME_DRAWN`; its receipt has `activity_reuse=true`. Cold cases
record Guest force-stop evidence. Both final runs used `attempt=1`, `retryBudget=0`, and no
automatic diagnostic retry.

## Affected capability closure

* P1-13 split/base/feature version coherence: API35 targeted rerun
  `out/verification/p1-16-api35-split-version-coherent-20260909` PASS; API36 exclusive rerun
  `out/verification/p1-13-api36-version-coherent-20260909` PASS. `apkanalyzer` reports base and
  feature both at `versionCode=1`, `versionName=1.0-split`.
* P1-14 WebView/GMS boundary remains the recorded API36 fixture PASS with GMS explicitly
  deferred to P2-10.
* P1-15 native boundary remains the recorded API36 normal-path PASS, with raw SVC/seccomp
  explicitly `EXPECTED_LIMITATION`; static audit remains 29/29 ELF and 25/25 APK native
  packaging PASS, and the existing real API35 x86_64/16 KB evidence remains coordinate-bound.

The earlier split mismatch and the contaminated S04 run are both retained as first-failure
receipts; neither is an unexplained final-gate failure.

## Build and harness checks

`gradlew.bat :app:assembleDebug :fixture-basic:assembleDebug` and the final runner's
`gradlew.bat test` both pass. `tools/static_android_compile.py` remains PASS with only its
existing four stub warnings. After the harness correction, `tools/verification/test_harness.py`
passes all 16 contract tests, and `python -m py_compile tools/verification/capabilities/smoke.py`
passes. `git diff --check` passes.

## Candidate and P2 handoff

The fixed-hash debug candidate is [P1-16_CANDIDATE_MANIFEST.json](P1-16_CANDIDATE_MANIFEST.json).
P2-01 must dynamically discover the real Xiaomi serial, model, fingerprint, API, ABI and page
size, then install the Host plus the ARM64-capable fixture using those exact hashes. The
`fixture-compat32` APK is conditional and must not be installed on an ARM64-only device without
an explicit requirement. Chrome/Trichrome/Quark source, split, signer and hash freezing is
deferred to P2-01; Chrome browsing to P2-02 and Quark business lifecycle to P2-03. ARM64 16 KB
remains conditional P2-11.

API32–34 full repetition, large regression, stress, and eight-hour stability remain intentionally
unstarted and are not part of this gate. API37 is recorded as unverified; its environment lane does
not block the current API36 Xiaomi handoff.
