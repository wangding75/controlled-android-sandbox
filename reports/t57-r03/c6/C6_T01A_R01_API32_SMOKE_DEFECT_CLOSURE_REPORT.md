# C6-T01A-R01 — API32 Core Smoke Defect Closure

## Result

- `RESULT=PASS`
- `START_HEAD=74508ab1db1ace0ac4a76302b0053beaffc9637a`
- `FINAL_HEAD=HEAD`（本报告与全部修复随本任务唯一提交落盘；精确 SHA 见最终回执）
- Branch: `feature/t57-r03-va-pro-capability-campaign`
- Device: MuMu `RD测试`, `127.0.0.1:16416`, Redmi `22041211A`, Android 12 / API 32, `x86_64`, page size `4096`
- Final run: `c6-t01a-rd-api32-20260902-r8`, `--no-diagnostic-retry`

The six API32 failures were diagnosed before changing product code. Three were CAS product defects, two were harness defects, and one was a debug-result observability defect. No fixture or environment defect remains in D01–D06.

## Root Cause Matrix

| Defect | Business operation | Actual business result | Debug/result path | Root cause | Classification |
|---|---|---|---|---|---|
| D01 / S04 | Activity warm reuse | Same virtual task was reused; R8 observed physical task reuse, `NEW_INTENT`, a fresh frame, and the same request-scoped result | The old observation token could collide with a previous operation, and the physical Host task was not brought front before reuse | `RC-01`: the Activity reuse contract did not complete the physical task projection and request-scoped observation boundary; singleton launcher task state was also rejected | `PRODUCT_DEFECT` |
| D02 / S05 | Service lifecycle | R8 completed one service cycle with create/start, exact startId, bind/unbind, foreground, and stop semantics | Framework start and broker operations used different service ownership/records, so completion could time out despite partial lifecycle activity | `RC-02`: framework-owned Service token/record, bridge, startId, and declared FGS type were not one shared state machine | `PRODUCT_DEFECT` |
| D03 / S06 | Broadcast dispatch | R8 returned `BROADCAST_CAMPAIGN_LAUNCHED` and the broadcast campaign completed | The next command could start while the prior Host/Guest session was still stopping; `SESSION_BUSY:STOPPING` polluted the precondition and the debug command reported failure | `RC-03`: Host controller task/process teardown was not scoped and fenced by semantic completion | `HARNESS_DEFECT` (test-state pollution subtype) |
| D04 / S07 | Provider access | The provider query returned `CURSOR_READY` with the required query marker and R8 passed | The product result was valid, but the assertion accepted only `OK` and converted a valid cursor state into `PROVIDER_QUERY_NOT_OK` | `RC-04`: provider result-state contract in the harness was narrower than the real operation contract | `HARNESS_DEFECT` |
| D05 / S08 | PendingIntent path | R8 completed `LAUNCH_PASS`; framework probe recorded PendingIntent creation, binder/callback delivery, and cross-package routing passes | A derived Guest context lost the process-wide ActivityThread service bridge and fell back to the separate manual runtime path; the command also needed a semantic Activity-created boundary | `RC-02`: framework component ownership/bridge propagation was not preserved across Guest contexts | `PRODUCT_DEFECT` |
| D06 / S09 | Package lifecycle | R8 completed add, clear, delete, launch, re-add, and relaunch with typed `IMPORTED`/`CLEARED`/`DELETED`/`LAUNCH_PASS` results | On API32 an obsolete Host controller transaction could crash with `Activity client record must not be null` before result publication, producing `DEBUG_RESULT_TIMEOUT` | `RC-03`: stale controller task/ActivityRecord state made debug completion publication observability-unsafe | `OBSERVABILITY_DEFECT` |

`ROOT_CAUSE_COUNT=4`; shared root causes are `RC-02` (D02+D05) and `RC-03` (D03+D06), so `SHARED_ROOT_CAUSE_COUNT=2`.

### True product defects

- **D01:** Completed singleton virtual-task reuse, physical Host task fronting, fresh `NEW_INTENT` delivery, and request-scoped first-frame observation.
- **D02:** Unified framework Service bridge/record ownership, exact framework startId propagation, broker bind/unbind/stop routing, and manifest-derived foreground-service type.
- **D05:** Preserved the ActivityThread service bridge through derived Guest contexts and made the launch command wait on the semantic Activity-created boundary.

### Harness and observability defects

- **D03:** Added command-scoped Host fencing and semantic activity teardown so a prior stopping session cannot contaminate the next broadcast case. This does not weaken the broadcast assertion.
- **D04:** Accepted the two valid provider terminal states (`OK` and `CURSOR_READY`) while retaining the required provider-query marker.
- **D06:** Added bounded package-stop/result fencing and Host-task filtering so an API32 controller ActivityRecord race cannot erase a completed command result. Timeout remains fail-closed.

No fixture source was changed and no environment exception was used to obtain PASS.

## Shared state-machine and implementation changes

The implementation keeps business semantics and explicit ownership; it does not add package-specific hooks, delays, retries-as-success, or swallowed exceptions.

- `app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java` and `app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java`: semantic launch completion, typed activity-count waits, and authoritative FGS-type lookup.
- `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedger.java`: valid singleton launcher-task reuse; `ActivityTaskLedgerSelfTest.java` covers it.
- `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeActivityLaunchCoordinator.java`, `RuntimeComponentOperationCoordinator.java`, and `RuntimeServiceCoordinator.java`: physical task reuse/fronting, request-scoped observation, and framework-owned service routing.
- `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/StubActivityBase.java` and `component/service/BrokerServiceRuntime.java`: fresh warm-reuse frame observation and manifest-derived FGS type.
- `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestActivityThreadServiceBridge.java`, `GuestActivityThreadServiceLifecycle.java`, `GuestComponentRuntime.java`, `GuestContext.java`, `GuestLaunchGate.java`, and `GuestLaunchObservation.java`, plus `runtime/protocol/RuntimeKeys.java`: one framework service lifecycle ledger, bridge propagation, stale-event rejection, and semantic readiness events.
- `sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestLaunchGateSelfTest.java`: stale pre-reuse lifecycle event regression coverage.
- `tools/verification/capabilities/smoke.py` and `tools/verification/device/adb.py`: scoped Host fencing, semantic teardown, valid provider terminal-state assertion, semantic component launch, and bounded package-stop waits.

## VA/NBB comparison

The requested local refs `ref/upstream/VirtualApp` and `ref/upstream/NewBlackbox` are not present in this checkout (`git show-ref` returned no matching refs). No upstream code was copied. The comparison used the existing CAS Git history and the known lifecycle contracts represented by those implementations:

- Activity: task affinity/launch mode/flags and a stable virtual task identity must be projected into Host task reuse and `NEW_INTENT` delivery.
- Service: framework service token, `IServiceConnection`, process startup, and lifecycle publication must refer to one service record.
- Broadcast: receiver routing and `PendingResult` completion are independent of the controller's result-file timing.
- Provider: authority mapping, provider process bootstrap/publication, acquisition, and query result state must be observed end-to-end.
- PendingIntent: creator identity, IntentSender token ownership, virtual user, and target routing must survive the Host holder relay.
- Package lifecycle: revision, process termination, storage cleanup, clear state, and relaunch must not reuse stale loaded/task state.

These references were used to check ownership/state-machine equivalence, not to copy code or restore the historical task runner. Local CAS history also confirmed the relevant earlier A01/C1/C2/C4 lifecycle fixes and exposed the regression boundary in the current implementation.

## Validation

Targeted and contract tests:

- `python -m unittest tools.verification.test_harness -v`: **PASS, 6/6**.
- `python -m compileall -q tools/verification`: **PASS**.
- `git diff --check`: **PASS**.
- Targeted Activity ledger and Guest launch-gate self-tests are included in the Gradle test graph; the final Gradle test completed successfully.

Build/test:

- `./gradlew.bat projects --console=plain`: **PASS**.
- `./gradlew.bat assembleDebug --console=plain`: **PASS**.
- `./gradlew.bat test --console=plain`: **PASS**.

Final real RD/API32 smoke:

| Case | Result |
|---|---|
| S01 Host install/start | PASS |
| S02 import/add | PASS |
| S03 cold first-frame | PASS |
| S04 warm launch reuse | PASS |
| S05 service lifecycle | PASS |
| S06 broadcast dispatch | PASS |
| S07 provider access | PASS |
| S08 PendingIntent path | PASS |
| S09 package lifecycle | PASS |
| S10 process death/recovery | PASS |

`SMOKE_TOTAL=10`, `SMOKE_PASS=10`, `SMOKE_FAIL=0`.

## False-pass check

`FALSE_PASS_CHECK=PASS`. S04 was not accepted from the virtual ledger alone: the compact evidence includes the physical `ATMS_ACTIVITY_TASK_REUSE` witness, Host task identity, a fresh `NEW_INTENT`, and a new requestId-correlated first-frame event. S07 still requires the provider-query marker and only recognizes its explicit valid terminal states. S05, S06, S08, and S09 require typed command results plus business markers; no timeout or missing result was converted to PASS.

## Evidence and remaining scope

- Compact run summary: `out/verification/c6-t01a-rd-api32-20260902-r8/` (ignored by Git).
- Raw logcat, screenshots, dumpsys, process dumps, rerun directories, and APK output remain outside Git.
- `git ls-files out` is empty; no `verification/catch-up/` directory was created.
- `EVIDENCE_GIT_HYGIENE=PASS`: only this compact report and the existing progress ledger are intended as documentation changes.
- No unresolved D01–D06 API32 product defect remains. API33–37, real ARM64, 16 KB page-size, and broader matrix work remain outside this closure and are not started here.
