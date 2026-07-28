# M4-T15 stage report

Date: **2026-07-28**

Baseline: `68a93bc9983d3a8fe8929ce992d4f56649a8af19` (`feat(m4-t14): harden guest service lifecycle`)

Status: **SOURCE/HOST PASS CANDIDATE — DEVICE NOT TESTED**

## 1. Stage objective

M4-T15 establishes a recoverable and queryable Activity/Task source baseline. The iteration targets task ownership and lifecycle semantics that were still volatile at M4-T14: selected launch flags, result forwarding, no-history behavior, recent/running task state and Runtime Broker restart restoration.

The scope excludes claims about Android system Recents, Window transitions, real Binder signature compatibility or third-party APK launch rates.

## 2. Delivered capabilities

| Area | Delivered result | Evidence boundary |
|---|---|---|
| Launch policy | Added bounded handling for no-history, forward-result, exclude/retain-recents, reset-task and new-document flags | Host/source tests only |
| Result routing | `FORWARD_RESULT` transfers result ownership and rejects invalid chains | Ledger tests; no device callback evidence |
| Task queries | Running/recent projections scoped by package and virtual user | Runtime Binder contract and tests |
| Task operations | Owner-checked move-to-front and remove-task | Internal runtime path; Guest AppTask adapter pending |
| Recent policy | Bounded deterministic recent history with exclusion and retention rules | Source model only |
| Restart recovery | Atomic CRC-protected checkpoint restores task/activity state | Broker restart host test |
| Security | Stale generations and cross-owner task operations fail closed | Unit/static integration tests |
| Transport cleanup | One-time route and pending-result authority are not revived after restart | Restore tests |

## 3. Implementation summary

- Added five immutable Activity/Task checkpoint and query models.
- Expanded `ActivityTaskLedger` with task query, mutation, result-forwarding, no-history and restoration behavior.
- Added `ActivityTaskCheckpointStore` with bounded decode, CRC32 verification, atomic replacement and corrupt-file quarantine.
- Added typed `ActivityTaskRequest`, `ActivityTaskResult` and `ActivityTaskSnapshot` contracts.
- Added `IRuntimeBroker.activityTaskOperation` and Runtime Broker dispatch.
- Added specialized source gate and integrated it into `verify-all.sh`.
- Expanded the capability evidence matrix by five entries.
- Added focused ledger, checkpoint-store and Broker-restart tests.

## 4. Evidence and test result

Confirmed local passes before final packaging:

- M4-T15 Activity/Task source gate.
- M3 capability evidence matrix validation.
- M4-T14 Service lifecycle regression gate.
- Static Android-source compilation using local API/AIDL stubs.
- Full Activity task ledger self-tests.
- Checkpoint persistence/corruption/quarantine self-test.
- Broker Activity production adapter and restart restoration self-test.
- Existing package, process, Service, Receiver, Provider, permission, framework-proxy and virtual-system-service static regression suite.

No Android SDK build, Emulator run, physical-device test or real third-party APK test is counted as completed.

## 5. Progress metrics

| Evidence dimension | M4-T14 | M4-T15 | Change |
|---|---:|---:|---:|
| Capability entries | 90 | 95 | +5 |
| Source complete | 86 | 91 | +5 |
| Source weighted | 97.8% | 97.9% | +0.1 percentage point |
| Production wired | 82 | 87 | +5 |
| Production weighted | 95.5% | 95.7% | +0.2 percentage point |
| Device verified | 0 | 0 | no change |
| Device weighted | 0.0% | 0.0% | no change |

The modest percentage increase is expected because the denominator also grew. The meaningful change is that five previously untracked Activity/Task requirements now have explicit evidence rows and gates.

## 6. Code-quality assessment

### Improvements

- Checkpoint input is bounded and integrity checked.
- Persistence uses temporary-file replacement rather than in-place overwrite.
- Task query and mutation enforce package, virtual-user and generation ownership.
- Restoration deliberately removes dead transient capabilities.
- New behavior is covered by a dedicated source gate and regression tests.

### Current liabilities

- `ActivityTaskLedger` has reached approximately 1,303 lines and mixes policy, state transition, recents and serialization concerns.
- `RuntimeBrokerService` remains a large central Binder service at approximately 1,370 lines.
- Checkpoint write failure does not roll back an already accepted in-memory task transition.
- The new task operation contract is typed, while older Activity launch/event Binder paths still use `Bundle` envelopes.
- Framework-facing Android task objects and API-version adapters are incomplete.

Judgment: the iteration improves runtime safety and observability, but architectural quality will decline if further Activity features are added directly to the same ledger and Broker classes. M4-T16 should include extraction, not only new behavior.

## 7. VA/NBB position

M4-T15 narrows the source-model gap in result routing, task recents and Broker restart recovery. VirtualApp and NewBlackbox still have broader Android framework interception, task/window integration and historical device compatibility work. Current Controlled Sandbox evidence cannot support functional equivalence or a percentage claim against VA/NBB.

See `docs/comparisons/M4_T15_VA_NBB_COMPARISON.md`.

## 8. Remaining uncertainty

- Real Android Binder signatures may differ from the local stubs.
- OEM task/Recents behavior is not represented by the source model.
- The checkpoint has not been tested under actual process kill, filesystem pressure or app upgrade on Android.
- Compound launch-flag behavior is incomplete.
- No measured real-App launch or 20-minute stability result exists.

## 9. Subsequent iteration plan

### M4-T16 — Android Activity/Task adapter and decomposition

Priority: highest.

- Extract task persistence, recents policy and result routing from `ActivityTaskLedger` into narrower components.
- Add bounded framework projections for running/recent tasks and `AppTask`.
- Route Guest task queries, move-to-front and remove-task calls through the Runtime Broker.
- Complete startActivity-for-result callback signatures and version policy.
- Add compound launch-mode/flag matrix tests.

Exit condition: source gates prove that Guest framework calls consume the Broker-owned task model without direct host identity leakage.

### M4-T17 — Service Android parity hardening

- Complete `IServiceConnection` callback adaptation.
- Enforce foreground-service notification deadline/type policy.
- Add Android-version background-start restrictions.
- Persist bounded sticky/redeliver recovery metadata.

Exit condition: Service lifecycle contract is framework-facing and version-policy controlled, with no stale Binder ownership after recovery.

### M4-T18 — Job, alarm and callback version adapters

- Add Job work-item handling and broader constraint/result callbacks.
- Harden Alarm reboot/power semantics.
- Complete Notification/Job callback ownership and recovery tests.

Exit condition: persistent virtual system-service resources have explicit reboot/recovery and version-adapter behavior.

### M4-T19 — Android build and Emulator evidence baseline

- Run locked AGP/NDK build.
- Execute Fixture Activity/task, Service, Receiver, Provider, WebView and JNI paths.
- Validate API-level task/back-stack behavior.
- Run the 20-minute zero-crash/zero-ANR gate.
- Publish evidence bundle and measured failures without converting source percentages into APK compatibility claims.

Exit condition: first reproducible device-evidence baseline, even if some fixtures fail.
