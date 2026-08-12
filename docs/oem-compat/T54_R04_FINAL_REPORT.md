# T54-R04 final report

Date: 2026-08-12  
Branch: `feature/ui-oem-compat`  
Result: **PASS**

## Track A — RD测试 / DingTalk

| Item | Result |
|---|---|
| Dynamic instance resolution | `RD测试` → `ginstance1400582734232539401` → `127.0.0.1:16416` |
| Device | Redmi `22041211A`, Android 12 / API 32 |
| DingTalk | `com.alibaba.android.rimet`, versionName `7.8.10`, versionCode `1178` |
| Formal launch | 5/5 PASS through Flash2 Home → Apps → DingTalk instance → Launch |
| Formal stop | 5/5 PASS through Flash2 Apps-row Stop; Guest process/session cleaned |
| Target FATAL / ANR | 0 / 0 |
| External runner interference | none during formal session; `com.cost.app`/`com.phoenix.read` stopped during preflight |
| Apps catalog | DingTalk remained present across all rounds |
| User session | `REAL_USER_SESSION_REQUIRED` for logged-in business verification |

Each round reached a real Guest DingTalk Activity, including generation-2
`com.alibaba.android.rimet.PrivacyPolicyActivity`. The observed
`checkExportedActivityStartup → System.exit(0)` event is classified as
`OBSERVED_APP_INITIATED_EXIT`; Guest recovery succeeded and the event did not produce Sandbox
FATAL/ANR. Raw preflight, UI XML/screenshots, logcat, Activity dumps, process snapshots, and the
round matrix are under `build/t54-r04-evidence/track-a/`.

## Track B — Pixel Android 16 / API36 JobScheduler

| Item | Result |
|---|---|
| AVD | `Pixel_Android16_API36_GoogleApis_x86_64` |
| Dynamic serial | `emulator-5554` resolved by `adb emu avd name` |
| Identity | Android 16, API 36, ABI `x86_64`, `boot_completed=1` |
| Fixture | Existing 64-bit `fixture-basic`, extended with one minimal JobService fixture |
| Schedule | PASS (`JobScheduler.schedule` result `1`) |
| Device callback | PASS: Host JobScheduler → `VirtualJobService` → Guest `onStartJob` |
| Finish | PASS: Guest `jobFinished` → Host job completion |
| Cancel / cleanup | PASS before execution; Host pending/active queues empty after cases |
| User isolation | PASS for user0/user1; separate Guest paths and projected Host job ids |
| Session rebuild | PASS; old delayed callback was not delivered to the rebuilt owner |
| FATAL / ANR | 0 / 0 after the lifecycle fix |

The fixture covers schedule/finish (`1801`), cancel-before-execution (`1802`), and delayed
session-rebuild ownership (`1803`). Evidence includes raw logcat, `dumpsys jobscheduler`, command
results, process snapshots, Guest file paths, virtual job ids, and projected Host job ids under
`build/t54-r04-evidence/track-b/`. The former
`SOURCE_BRIDGE_PASS_DEVICE_CALLBACK_NOT_RUN` state is upgraded to `PASS_RUNTIME_API36`.

## API32 targeted regression

On RD测试 after installing the rebuilt host/runtime and fixture:

- `component-suite`: PASS for user0 and user1;
- Activity launch/stop: PASS for user0 and user1;
- Job schedule → real Guest callback → finish: PASS for user0 and user1;
- clean test windows: FATAL=0, ANR=0.

Static checks `check-guest-jobservice-bridge.py` and `check-job-scheduler-policy.py` also passed.
The full M3 20-minute run was not repeated because the change was limited to JobService lifecycle
and fixture coverage.

## T54-R03 status correction

`docs/oem-compat/T54_R03_FINAL_REPORT.md` remains `PASS_WITH_ENVIRONMENT_LIMITATIONS`, now with
only real environment limitations retained:

- `AVD_CAMERA_HAL_LIMITATION` for legacy Camera1 on the API36 AVD;
- 32-bit ABI unsupported on the x86_64 API36 AVD;
- `NOT_RUN_NO_LOCAL_APK` for Quark API36;
- Xiaomi HyperOS/API36 `REAL_DEVICE_VERIFICATION_PENDING`.

Runner interference and untested device callback are no longer listed as environment limitations.

## Git and scope

No Xiaomi HyperOS, F6, main merge, source backup package, Git bundle, rebase, squash, amend, or
force push was performed.
