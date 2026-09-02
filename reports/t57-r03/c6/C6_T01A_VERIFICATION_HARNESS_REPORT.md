# C6-T01A — Unified Android Verification Harness Foundation

- Overall result: `PASS_WITH_DISCOVERED_PRODUCT_DEFECT`
- START_HEAD: `94415f9523161d08983790b07d2a397ba4c5d633`
- FINAL_HEAD (source baseline): `6b03d5ca95f45d48bbcadab56fb0beddab3aa287`
- Branch: `feature/t57-r03-va-pro-capability-campaign`

## Architecture

Capability-oriented Python harness under `tools/verification/`, split into strict contract models/policies, reusable ADB device access, real S01–S10 capabilities, JSON schemas and compact reporting. Existing `scripts/mumu_instance.py` resolves the MuMu `RD测试` instance; no serial is embedded in the new source. Generated evidence is isolated under `out/verification/<run-id>/` and is not source/test data.

## Implemented files

- `.gitignore` — ignores generated `out/verification/` evidence.
- `scripts/mumu_instance.py` — exposes the existing resolver's bounded ADB runner for reuse; instance-name resolution remains the single source of truth.
- `tools/verification/core/` — testcase contract, fail-closed retry policy, timeout policy and readiness assertions.
- `tools/verification/device/` — ADB wrapper, dynamic device metadata and non-black PNG inspection.
- `tools/verification/capabilities/smoke.py` — real RD/API32 S01–S10 smoke operations.
- `tools/verification/reporting/` and `tools/verification/schemas/` — normalized summary/report and machine-readable contracts.
- `tools/verification/test_harness.py`, `tools/verification/run_rd_smoke.py` — self-tests and execution entry point.

## Smoke cases

| ID | Capability | Result | Failure class | Duration ms |
|---|---|---:|---|---:|
| S01-host-build-install-launch | package | PASS | — | 4773 |
| S02-guest-import-add | package | PASS | — | 15303 |
| S03-cold-launch-first-frame | activity | PASS | — | 27610 |
| S04-warm-launch-reuse | activity | FAIL | PRODUCT_DEFECT | 37819 |
| S05-service-lifecycle | service | FAIL | PRODUCT_DEFECT | 247608 |
| S06-broadcast-dispatch | receiver | FAIL | PRODUCT_DEFECT | 68742 |
| S07-provider-access | provider | FAIL | PRODUCT_DEFECT | 172095 |
| S08-pending-intent-path | pending_intent | FAIL | PRODUCT_DEFECT | 219159 |
| S09-package-lifecycle | package | FAIL | PRODUCT_DEFECT | 350324 |
| S10-process-death-recovery | process | PASS | — | 64380 |

Smoke total/pass/fail: `10` / `4` / `6`; blocked/unsupported: `0` / `0`.

## Device metadata

- Instance/serial: `RD测试` / `127.0.0.1:16416`
- Manufacturer/model: `Redmi` / `22041211A`
- API/Android: `32` / `12`
- ABI/ABI list: `x86_64` / `x86_64, arm64-v8a, x86, armeabi-v7a, armeabi`
- Page size: `4096`
- Fingerprint: `Redmi/rubens/rubens:12/V417IR/2428:user/release-keys`
- Kernel: `Linux localhost 5.4.32-perf-gda349bfae95e #3 SMP PREEMPT Wed Aug 19 10:55:20 UTC 2026 aarch64`
- CAS commit: `94415f9523161d08983790b07d2a397ba4c5d633`
- APK hashes recorded: `4`

## Build and harness tests

- `gradlew.bat projects`: `PASS`
- `gradlew.bat assembleDebug`: `PASS`
- `gradlew.bat test` (or actual unit tasks): `PASS`
- Harness self-tests: `PASS`

## Defects and limitations

- `DISCOVERED_PRODUCT_DEFECT` — `S04-warm-launch-reuse`; reproduce with `DebugCommandActivity launch without stopping Host`; signature `ACTIVITY_REUSE_NOT_OBSERVED`; the first failure remains FAIL even if a diagnostic retry passes; next recommended task: C6-T01B product correction and rerun.
- `DISCOVERED_PRODUCT_DEFECT` — `S05-service-lifecycle`; reproduce with `DebugCommandActivity service-lifecycle-suite iterations=1`; signature `DEBUG_RESULT_TIMEOUT`; the first failure remains FAIL even if a diagnostic retry passes; next recommended task: C6-T01B product correction and rerun.
- `DISCOVERED_PRODUCT_DEFECT` — `S06-broadcast-dispatch`; reproduce with `DebugCommandActivity broadcast-campaign iterations=1`; signature `DEBUG_COMMAND_NOT_PASS`; the first failure remains FAIL even if a diagnostic retry passes; next recommended task: C6-T01B product correction and rerun.
- `DISCOVERED_PRODUCT_DEFECT` — `S07-provider-access`; reproduce with `DebugCommandActivity neighbor-provider`; signature `DEBUG_RESULT_TIMEOUT`; the first failure remains FAIL even if a diagnostic retry passes; next recommended task: C6-T01B product correction and rerun.
- `DISCOVERED_PRODUCT_DEFECT` — `S08-pending-intent-path`; reproduce with `import peer; launch FrameworkProbeActivity`; signature `DEBUG_COMMAND_NOT_PASS`; the first failure remains FAIL even if a diagnostic retry passes; next recommended task: C6-T01B product correction and rerun.
- `DISCOVERED_PRODUCT_DEFECT` — `S09-package-lifecycle`; reproduce with `import -> launch -> clear -> launch -> delete -> import`; signature `DEBUG_RESULT_TIMEOUT`; the first failure remains FAIL even if a diagnostic retry passes; next recommended task: C6-T01B product correction and rerun.
- This C6-T01A foundation executes the current RD/API32 smoke set; API33-37 adaptation and the ARM/16KB matrix remain in later C6 tasks.
- The harness records real readiness and screen evidence; it never promotes an accepted/pending launch or a black frame to PASS.

## Evidence and Git hygiene

- Local evidence: `out/verification/c6-t01a-rd-api32-20260902-final2`
- `git status --short`: `CLEAN`
- `git diff --stat`: `(clean)`
- `git ls-files out`: `(none)`
- Raw logs, dumps, screenshots and command JSON remain only in the ignored run directory; the report contains paths and summaries, not full logs.
