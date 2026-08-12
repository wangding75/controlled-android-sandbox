# T54 Final Global Review

Review date: 2026-08-12

## Result

**T54-R05: PASS**

**T54 FINAL RESULT: PASS**

**T54 FROZEN**

Global review conclusion: P0 = 0, P1 = 0. No new feature work was required. The remaining findings are explicit P2 follow-ups and real environment/business-session boundaries.

## Git baseline

- T53 baseline commit: `3a8c998ffd58dcb158f548df64d8d80590cf338c`
- T53 baseline tree: `ca1b800f50e2ebf745226e8200ad8714ee27b081`
- Source review HEAD before this report-only freeze: `eb4cdabb1dbb60c251975205acc1487b2515f40c`
- Source review tree before this report-only freeze: `aa2f8eae4e3d5f89d0c293ea6957fcdf1c9711ee`
- Branch: `feature/ui-oem-compat`
- T53 to review HEAD: 72 files changed, 4185 insertions, 372 deletions

The final report commit and pushed HEAD are recorded by the final Git gate in the task receipt. No amend, rebase, squash, force push, merge-main, ZIP, or Git Bundle was used.

## Review scope

The review covered the T53-to-HEAD diff, app/Flash2 UI, sandbox SDK and adapter boundaries, contract, framework, runtime, native trust, fixtures, runners, compatibility code, reports, saved R02/R03/R04 evidence, and Git state.

The review specifically searched for false PASS patterns, suppressed exceptions, stale callback acceptance, app-specific Core branches, API32/API36 split implementations, runner-created results, deleted gates, stale APK evidence, and report/source contradictions.

## Source and architecture findings

### Flash2 UI

The normal UI is the current Flash2 shell with Home, Apps, Me, and Instance Settings. F2, F3, F4, F5, DingTalk compatibility, and Developer Diagnostics are exposed through the application layer/typed adapter boundary. Screens do not directly depend on Runtime or native internals. Unsupported map picking is visibly marked `NOT_IMPLEMENTED`; Camera1/Camera2 runtime compatibility is not exposed as a user switch. F2/F5 reset operations remain scoped resets.

The old SX UI is retained as visual/business reference only. No old SX Runtime/Hook/BlackBox implementation was found reintroduced. Normal Home vocabulary does not expose Debug/M3 terms, and no fake product button was found.

### Activity, Task, and Window

The StubActivity fix is a real identity fence, not a catch/suppress workaround. The owner includes package, virtual user, session, generation, process slot, activity token, and task identity. Stale callbacks are rejected, ActivityClientRecord updates are checked against the current framework token, and detached window records are repaired while preserving framework addView and forward-navigation semantics.

The focused self-test passed:

`PASS StubActivityWindowOwnershipSelfTest oldBehavior=REPRODUCED_AND_REJECTED`

No forced finish, delay/sleep workaround, task-switching disablement, fixture/package/RD/MuMu/OEM special case, or swallowed Window exception was found.

### API36 package alias

The Android 16 `getAppTasks` package alias is used only for virtual-handling recognition. Results are projected from the Guest virtual task ledger, and the Host package alias is not passed through as Guest identity. The implementation is generic and has no Pixel/AVD/Camera-specific Core branch. API32 targeted regression remained passing.

### JobScheduler

The R04 fixture exercises the real callback chain:

`Guest schedule -> Sandbox virtualization -> Host JobScheduler projection -> VirtualJobService -> Guest JobService.onStartJob -> jobFinished -> cleanup`

The retained evidence covers schedule/callback/finish, cancellation and cleanup, user0/user1 isolation, session-rebuild stale callback rejection, and Host job queue cleanup. Virtual and projected Host job IDs are separate; callback ownership checks package, virtual user, session/generation, process, and dispatch token. Pending and active jobs are cleaned on stop/cancel and stale callbacks are not delivered to a rebuilt owner.

## Runtime acceptance

### API32 / RD

- M3 short: 10/10, 128/128, companion probes 64, FATAL/ANR 0, teardown PASS.
- M3 formal: 1200/1200 seconds, 18/18 simultaneous Guest slots, user0/user1 isolation, companion probes 8, FATAL/ANR 0, teardown PASS.
- The M3 evidence is tied to the repaired Window lifecycle source and retained external evidence. Later API36 alias/Job changes were covered by their targeted checks and R04 device runs; no claim is made that the old M3 run exercised those later additions.
- Quark `10.10.5.1080`: 3/3 launch and 3/3 stop on RD.
- R04 API32 targeted regression: component-suite u0/u1 PASS, launch/stop u0/u1 PASS, Job schedule/callback/finish u0/u1 PASS, FATAL/ANR 0.

### API36 / Pixel

Environment: `Pixel_Android16_API36_GoogleApis_x86_64`, Android 16, API 36, x86_64, boot completed, dynamically resolved serial `emulator-5554` for the R04 session.

Validated 64-bit paths include package/import, native trust, Activity/Task, Service, Receiver, Provider, JobScheduler, F2, F3 Camera2, F4, F5, product UI, and 5-minute 36/36 stability. FATAL = 0 and ANR = 0. The API36 JobScheduler fixture produced real device callback evidence for the R04 cases; `SOURCE_BRIDGE_PASS_DEVICE_CALLBACK_NOT_RUN` is closed and is not an environment limitation.

### DingTalk

R04 formal Track A used RD instance `RD测试`, dynamically resolved serial `127.0.0.1:16416`, and the Flash2 path `Apps -> DingTalk instance -> Launch`.

- Package: `com.alibaba.android.rimet`
- Version: 7.8.10 / 1178
- Product UI launch: 5/5
- Product UI stop: 5/5
- Guest reached PrivacyPolicyActivity or a real DingTalk pre-login Activity
- Apps catalog retained; Guest process/session cleanup and subsequent relaunch were clean
- FATAL = 0; ANR = 0; no external runner pollution
- `System.exit(0)` is classified only as `OBSERVED_APP_INITIATED_EXIT`; it is not relabeled `EXPECTED_APP_EXIT`
- `REAL_USER_SESSION_REQUIRED` remains for logged-in business acceptance

No logged-in business, DingTalk Camera business, or DingTalk Location business PASS is claimed.

## Delivered capability classification

The complete matrix is in [T54_FINAL_CAPABILITY_MATRIX.md](T54_FINAL_CAPABILITY_MATRIX.md). In summary: API32 and API36 virtual component/runtime paths are PASS within their tested ABI/device boundaries; F2 static/trajectory contract is PASS with map picker `NOT_IMPLEMENTED`; Camera1 API36 remains an AVD HAL limitation; Camera2 passes on API32/API36; F4/F5 virtual contracts pass without bypassing Android security or claiming unavailable physical hardware.

## Static/build review

The required Gradle source builds passed, including contract, framework, runtime, native CMake, app, and the fixture/job modules. The following structural/boundary checks passed: T54 compatibility, package/service boundary, Guest boundary, Guest JobService bridge, JobScheduler policy, broadcast model, native trust, package boundaries, and related receiver/accessibility boundary guards.

The activity virtualization structural gate still reports the known BrokerActivityRuntime threshold finding. The static Android compiler still stops in its test-infrastructure platform stubs with the known approximately 108 missing Android stub symbols. These are recorded as P2; the Gradle source build is not broken.

## P0 / P1 / P2

### P0

0.

### P1

0. No Core app-specific package behavior, stale owner acceptance, catch-and-fake success, API32/API36 incompatible implementation split, or evidence contradiction met P1 severity.

### P2

- P2-01 `BrokerActivityRuntime`: 412 lines against the unchanged 330-line architecture threshold. The gate remains active and the threshold was not raised or deleted. Follow-up: `P2_ARCHITECTURE_FOLLOW_UP`.
- P2-02 `tools/static_android_compile.py`: approximately 108 Android platform/test-infrastructure stub gaps remain. Gradle real compilation passes. Follow-up: `P2_TEST_INFRASTRUCTURE_FOLLOW_UP`.
- P2-03 `PeripheralServicesInvocationInterceptor`: 846 lines against its unchanged 500-line structural threshold. No runtime P0/P1 defect was found; the gate remains active. Follow-up: peripheral-services architecture decomposition.

P2 findings do not invalidate the runtime capabilities listed in the matrix, but they are not hidden or reported as PASS.

## Environment limitations

Only these real boundaries remain:

1. `AVD_CAMERA_HAL_LIMITATION` for Camera1 on the Pixel API36 AVD.
2. Current 32-bit fixture ABI is unsupported on the Pixel API36 x86_64 AVD.
3. Quark API36 is `NOT_RUN_NO_LOCAL_APK`.
4. Xiaomi HyperOS/API36 is `REAL_DEVICE_VERIFICATION_PENDING`.

The following are closed and are not limitations: runner interference, Window FATAL, API36 package mismatch, and untested device Job callback.

## Pending real-device and business verification

- Xiaomi HyperOS/API36 real-device verification remains pending.
- DingTalk logged-in business verification remains `REAL_USER_SESSION_REQUIRED`; this is a business-session boundary, not a Runtime failure.

## Next tasks

No T55/T56/T57 work is opened by this review. The only follow-ups are the three P2 architecture/test-infrastructure items above and the explicitly pending Xiaomi/real-user business verification.

## Freeze statement

With P0 = 0, P1 = 0, valid M3 evidence, valid R04 DingTalk and API36 Job callback evidence, API32 regression PASS, correct limitation classification, no fake PASS, and a clean pushed Git gate, T54 is frozen at this acceptance boundary.
