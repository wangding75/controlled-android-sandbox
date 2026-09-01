# C4-R05 formal two-round failure classification — 2026-09-01

## 1. Task and decision

- Task: `C4-R05` — MuMu `RD测试` formal two-round acceptance and closure.
- Final status of this run: `BLOCKED`; C4 remains open and no later task is eligible.
- Formal output: `verification/catch-up/C4-R05/formal-two-round-20260901-hot-barrier/`.
- Formal command completed only round 1 clean-install/cold. Round 2 retained-state/hot/recovery,
  C1/C2/C4/SX regressions, and the two-user short pressure test were not started after the
  first non-LOW_MEMORY terminal failure.
- Start baseline under test: clean commit
  `58e86b09cf8a6671e3d064042976ba5487c57ec2`, branch
  `feature/t57-r03-va-pro-capability-campaign`, with local and remote HEAD equal.

The formal lane was stopped fail-closed. The prior session interruption is preserved as an
interrupted child lane and is not counted as a pass or as a product failure.

## 2. Environment and build identity

- Formal start/end: `2026-09-01T11:21:45.130817+08:00` to
  `2026-09-01T12:58:29.787644+08:00`.
- MuMu instance: dynamically resolved `RD测试`, index `1`,
  `MuMuPlayer-12.0-1`.
- Device at formal start: serial `127.0.0.1:16416`, API `32`, model `22041211A`,
  manufacturer `Redmi`, release `12`, ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`, Android ID `398eea33120cd887`.
- Boot before the permitted recovery: `de531bad-89f7-4470-ae4c-408a70bfdf43`.
- Boot after the permitted recovery: `fbd12b02-b1dd-49e0-9946-6f94b0da4a64`.
- APK SHA-256 at the clean build:
  - host `app/build/outputs/apk/debug/app-debug.apk`:
    `8a65e3670bd97f85aa86536b1c7ca7a376578efe085dabf35abbfca589ba4c14`
  - fixture `fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk`:
    `8d8f4d776b287ea947358690882a33763c3967578952cc88e57d5188ca8275a5`
  - companion32 `sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk`:
    `c064e04f1e90810b7fdc9f71e8d8f9478ec37b9285a9d40f739610a7fbd0c7a7`
  - fixture32 `fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk`:
    `9193694ee36848992e98d7e1ff7197a833182807784eb5a375f0d32bff5c96e1`

The build record is `.../commands/000-build-clean-commit.json`; it returned `0` in `20,889 ms`
and reported no missing APK.

## 3. First-failure evidence and retry boundary

### 3.1 Durable interruption lane

`attempt-001` returned `124` after the host-side session was interrupted while three atomic
case rows had already been written. It had no failure row and no completed summary. Those three
rows were retained as evidence; the continuation seeded only their durable coordinates and
started at the next coordinate. This is not an acceptance pass and is not the first product
failure.

### 3.2 First actual failure: host-scoped LOW_MEMORY

The first actual formal failure was DingTalk (`com.alibaba.android.rimet`), user `1`, hot
iteration `19`, in `attempt-002`:

- request: `71304b9029ce4641b1d0307b4777fab9`
- operation: `71304b9029ce4641b1d0307b4777fab9-launch`
- runner operation: `c4-r03-dingtalk-u1-hot-19-a2-71304b9029`
- attempt `2`, retry budget `0`, `retryable=false`,
  `automaticRetryPerformed=false`
- started `12:55:21.188545`, completed `12:56:51.855749`, elapsed `90,667 ms`
- command result: `ERROR`, `RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout`
- classification: `LAUNCH_RESULT_NOT_PASS`

The first-failure snapshot contains both the structured exit record and the Host logcat line:

```
process=com.warden.controlledsandbox.debug
reason=3 (LOW_MEMORY)
timestamp=2026-09-01 12:55:38.513
pss=188MB
rss=246MB
lowmemorykiller: Kill 'com.warden.controlledsandbox.debug' ... reason: device is not responding
```

The case screenshot and Surface were non-empty, but the correlated command result was missing;
they do not override the command failure. The complete evidence is under:

`verification/catch-up/C4-R05/formal-two-round-20260901-hot-barrier/round-1-clean-install-cold/launch-matrix/attempt-002/attempts/dingtalk/user-1/hot-019/`

The key artifact hashes are:

- `case.json`: `b59b17945188eb327ee216507b5ec7991d55eae6885c32b8e9fa4e48a99dfdbc`
- `first-failure-full/application-exit-info.txt`:
  `fed84fe31095b67d64b6538eb3040b6f21b02c0ac11b20b89ed5a0bf07a717bc`
- `first-failure-full/logcat.txt`:
  `b42d56fc6a304adb3d6c105995962e62a110def84a912b0ea36d73b0ffa71fc7`

The runner therefore took the only permitted environment exception. It dynamically resolved
`C:\\Program Files\\Netease\\MuMu Player 12\\nx_main\\MuMuManager.exe` and executed
`control --vmindex 1 restart`; the operation returned `0`, changed the boot ID, and reached a
new `device` state in `17,915 ms`. Evidence:

`verification/catch-up/C4-R05/formal-two-round-20260901-hot-barrier/round-1-clean-install-cold/launch-matrix/low-memory-recovery/event-001/restart.json`

Restart artifact SHA-256:
`cdedfd334e8a6e475403d8d07b7e0b2f4c0d952e0df72a498abc54aa36d51a45`.

### 3.3 Terminal failure after the permitted recovery

`attempt-003` was a separately recorded manual continuation with a new request and new boot;
it was not an automatic retry and did not receive another restart. The same coordinate failed
the formal hot SLO:

- request: `fbfdb9b18d0e413b8471199e13e56254`
- operation: `fbfdb9b18d0e413b8471199e13e56254-launch`
- runner operation: `c4-r03-dingtalk-u1-hot-19-a3-fbfdb9b18d`
- target/package: DingTalk / `com.alibaba.android.rimet`
- user/mode/iteration: `1` / `hot` / `19`
- attempt `3`, retry budget `0`, `retryable=false`,
  `automaticRetryPerformed=false`
- started `12:57:47.236754`, completed `12:58:25.662946`, elapsed `38,426 ms`
- command status: `PASS`; operation status: `LAUNCH_PASS`
- correlated component: `com.alibaba.android.rimet.biz.LaunchHomeActivity`
- `ACTIVITY_RESUMED=true`, `FIRST_FRAME_DRAWN=true`, `windowEvidence=true`
- readiness: `18,345 ms` versus hot deadline `10,000 ms`
- final classification: `READINESS_SLO_EXCEEDED`, `failureDetected=true`

The readiness timeline is:

`REQUEST_ACCEPTED@65459 → GUEST_READY@77265 → ACTIVITY_LINK:com.alibaba.android.rimet.PrivacyPolicyActivity@83448 → GUEST_READY@83660 → LIFECYCLE_CREATED@83746 → LIFECYCLE_STARTED@83753 → LIFECYCLE_POST_CREATED@83764 → ACTIVITY_RESUMED@83769 → FIRST_FRAME_DRAWN@83804`

Window/Surface/screenshot evidence remained valid: `windows_empty=false`, `drawn=true`,
`surfaceNonEmpty=true`, screenshot `028afc4ed1a578bf92b1e051ac36fcf17e609451764cd5e7270282b097e6638f`,
non-black fraction `0.975022`, `uniform=false`, and no fatal markers. The full snapshot still
contains Activity, process, Window, Surface, ViewRoot, logcat, exit-info, host/Guest files,
transaction, catalog, revision, and screenshot artifacts.

The terminal evidence is under:

`verification/catch-up/C4-R05/formal-two-round-20260901-hot-barrier/round-1-clean-install-cold/launch-matrix/attempt-003/attempts/dingtalk/user-1/hot-019/`

Key hashes:

- `case.json`: `6a2b7c8793dc6d060ee726ffada115422dfb7117d8d70f159d29a1115948b7fa`
- `first-failure-full/logcat.txt`:
  `402643fc5c5f5bc02d0734c1e167973417fdc005c269f1e5a5f0d461d8a2bcb6`
- `first-failure-full/application-exit-info.txt`:
  `0022de92b6d817a49fbd13d4c8d20578069c54f469b7a2e404a695310fab63ca`

## 4. Timing and classification

The post-restart request's correlated stage timing was:

- `PACKAGE_STATE`: `5,262 ms`
- `PACKAGE_UNIVERSE`: `4,338 ms`
- `BROKER_CONNECT`: `11,849 ms`
- primary `GUEST_PREPARE`: `11,653 ms`
- `FIRST_FRAME_DRAWN`: `18,345 ms` from request acceptance.

The logs also contain unrelated/system memory-pressure observations, including LMK kills of
`android.process.acore` and `com.android.keychain`, and a separate `GUEST_NOT_PREPARED` receiver
operation. They are preserved in the full log, but are not promoted to a unique DingTalk or CAS
root cause without a controlled reproduction.

Classification is therefore bounded as follows:

1. The original `attempt-002` failure is an RD/MuMu Host process-owner and memory-pressure
   boundary, proven by Host-scoped `ApplicationExitInfo LOW_MEMORY`.
2. The independent `attempt-003` failure is a CAS recovery/readiness SLO failure after the new
   boot: the prior in-memory hot state was not available, and package/broker/Guest preparation
   consumed the hot budget. It is a real formal failure even though the eventual frame was valid.
3. Current evidence does not uniquely prove a DingTalk SDK/UI defect, a Window/Surface defect,
   or a single CAS method as the sole cause. The open boundary is
   `RD/MuMu memory pressure + post-restart CAS/Guest recovery-startup latency` and remains
   `NEEDS_REPRODUCTION_AND_CLASSIFICATION`.

This explains why a long prefix of rows can pass and the first row after the restart can fail:
the prefix exercised the warmed process/session state; the allowed reboot created a new boot and
the resumed hot coordinate paid recovery/package/broker startup costs. That is causal context,
not grounds to rewrite the first failure or waive the SLO.

## 5. VA/NBB comparison and repair boundary

The required reference implementations and the current C4-R05 designs were reread before this
classification. VA `VActivityManagerService.startProcessIfNeedLocked/processDead`, VA
`ActivityStack.startActivityProcess/processDied`, and VA `VirtualRuntime.crash`, together with
NBB `BProcessManagerService.startProcessLocked`, `ActivityStack.startActivityProcess`, and
`BActivityThread.bindApplication`, all model process death as a real owner boundary: the next
launch binds a new process record and does not use an Activity marker or an old process as proof
of success.

CAS follows that boundary through `RuntimeGuestLifecycleCoordinator` and the generation-fenced
`GuestRecoveryPrewarmCoordinator`, while adding translated package/index/broker work before a
Guest Activity can draw. The current evidence shows that this recovery work can exceed the hot
budget after a full VM restart; it does not justify a package-specific branch, a cached success
marker, a fixed sleep, a deadline extension, or a hidden retry. The existing hot Host Activity
teardown barrier remains valid and was not falsified by this failure.

Regression/fix disposition:

- R04 failure-injection and recovery commands: `PASS`.
- Reduced R02 add gate: `PASS` (fixture 25; DingTalk, Quark, Hongguo, Fanqie 5 each; zero
  observed add-gate failures in this formal command).
- R03 launch matrix: `188` terminal coordinates observed out of `500` expected; fixture `100`,
  DingTalk user0 `50`, DingTalk user1 `38` including the terminal hot-019 failure; Quark,
  Hongguo, and Fanqie launch rows were not reached.
- Round 2, regressions, and pressure: not executed after the terminal failure.
- No source repair is claimed by this receipt for the post-restart latency; a clean, bounded
  recovery/startup fix is required before a new formal run.

## 6. Retry, deviation, and recovery conditions

- No automatic retry was performed in any failed row; every recorded attempt had retry budget `0`.
- `attempt-001` was a user/session interruption and was resumed only from durable case rows.
- `attempt-002` was the first actual failure and received exactly one policy-approved,
  dynamically recorded LOW_MEMORY restart.
- `attempt-003` was the one independent post-restart observation. Because its snapshot did not
  prove a new Host-scoped LOW_MEMORY event, the wrapper stopped; no further retry was allowed.
- No deadline was expanded, no fixed readiness sleep was introduced, and no static marker,
  Guest-process existence, screenshot, or late frame was used to replace the command contract.
- Deviation from the R05 task book: the two-round formal acceptance is incomplete because the
  first formal round failed; this is explicitly recorded as `BLOCKED`, not `DONE`.

Recovery requires a new clean commit and evidence-driven repair or environment correction that
separately proves the Host process-owner/memory-pressure boundary and bounded post-restart
CAS/Guest readiness. Then R05 must restart with a new raw output directory and complete both
rounds, all commercial launch/add gates, regressions, and the two-user 15-minute/50-cycle
short test. Historical rows from this run must not be merged into a future PASS.

