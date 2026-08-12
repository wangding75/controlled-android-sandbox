# T54-R02 final report

Date: 2026-08-12  
Branch scope: `feature/ui-oem-compat`  
Result: **PASS**

## RD测试

| Field | Result |
|---|---|
| Instance | `RD测试` |
| Index / instance id | `1` / `ginstance1400581448817661401` |
| VM / state | `MuMuPlayer-12.0-1` / `start_finished` |
| Resolved serial | `127.0.0.1:16416` |
| Manager ADB port | `16416` |
| Device | Redmi `22041211A`, Android 12 / API 32 |
| Android ID | `398eea33120cd887` |
| Runtime state | `adb get-state=device` |

The target is name-based `RD测试`; the serial is session connection data. Resolution evidence is
in [RD_TEST_DEVICE_RESOLUTION.md](D:/github/controlled-android-sandbox/docs/runtime/RD_TEST_DEVICE_RESOLUTION.md).
No SX测试 operation was used in this R02 session.

## App baseline

Quark was retained and not uninstalled, reinstalled, or cleared:

- Package: `com.quark.browser`
- Version: `10.10.5.1080` / code `1080`
- Installed: `2026-08-12 15:56:30`; `firstInstallTime=lastUpdateTime`
- Device APK path: `/data/app/~~dxqs47PCezQbzoDLza5zug==/com.quark.browser-l7Xqhs0A5N3U6AmJPIuObg==/base.apk`
- APK SHA-256: `2cb38172da5da4aee03826da0feccb77ff0391ee7356f1562052ddc1fae9ecb3`

DingTalk was reinstalled exactly once from the requested local APK, with no network or old APK:

- Source: `C:\Users\wangding\Downloads\dingding.apk`
- Size: `262072301` bytes
- Source SHA-256: `2A1E50C004D97530CDAD65D65BCA87E06F8AA031072EC29574E5DE7971D320BB`
- Package: `com.alibaba.android.rimet`
- Version: `7.8.10` / code `1178`
- Device APK path: `/data/app/~~9Fp6GoZ2nwC8LbAz3s1AGw==/com.alibaba.android.rimet-oiThkVakZx8W3g__GP9jMg==/base.apk`
- Installed: `2026-08-12 16:02:20`; `firstInstallTime=lastUpdateTime`

## Root cause

The FATAL was:

`java.lang.IllegalArgumentException: View=DecorView@[StubActivity6/7] not attached to window manager`

from `WindowManagerGlobal.findViewLocked → updateViewLayout → ActivityThread.handleResumeActivity:5025`.
The current Stub Activity's ActivityClientRecord still retained its Window and `Activity.mWindowAdded`
remained true, while the same DecorView had already disappeared from `WindowManagerGlobal.mViews`
(`windowRootCount=0`, `windowRegistered=false`). On the RD reproduction, pause/resume pressure could
remove the root without delivering a usable detach callback; the framework then updated the stale
root after `onResume()` returned.

The stale ownership tuple was not limited to a package: it crossed lifecycle timing around Stub slot
6/7, task switching, Activity token/relaunch state, generation and virtual user. The forward-
navigation bit selected the framework update branch and affected exposure probability, but it was not
the ownership root cause. Prior attempts were insufficient because direct `addView` did not repair
ActivityThread's record, hidden-API Java reflection was denied on the target, post-`super.onResume`
repair was too late, and clearing pending/preserve state alone did not invalidate the stale record.

Detailed event order and identities are in [T54_R02_WINDOW_FATAL_TIMELINE.md](D:/github/controlled-android-sandbox/docs/runtime/T54_R02_WINDOW_FATAL_TIMELINE.md).

## Code fix

Commits:

- `6e305a89 fix: repair stale stub activity window records`
- `4d143402 test: harden bounded lifecycle evidence capture`

The generic Runtime fix:

- adds a generation/session/user/slot/token/task Stub window ownership fence;
- detects missing WMG roots before `super.onResume()` when the framework marker is stale;
- uses a typed NativePolicy JNI bridge to verify the exact `ActivityClientRecord` by framework token,
  clear stale window/preserve/pending fields, and clear `mWindowAdded`;
- lets `ActivityThread` perform its normal `r.window == null → addView` transition;
- preserves task switching, forward-navigation semantics, and framework exceptions;
- adds complete window/owner diagnostics without DingTalk, Quark, RD, MuMu, Samsung, or OEM branches.

No exception is swallowed, no Activity is forcibly finished, and no delay or task-switch disablement
is used.

## Unit and static tests

- `StubActivityWindowOwnershipSelfTest`: PASS; old stale-callback behavior is deliberately reproduced
  and rejected, covering all five required cases.
- Gradle compile: PASS for `sandbox-framework`, `sandbox-runtime`, `sandbox-native`, `app`,
  `sandbox-companion32`, and `fixture-compat32`.
- `check-t54-android-compat.py`: PASS.
- Guest boundary: PASS.
- Package service boundary: PASS.
- Broadcast/receiver architecture checks: PASS.
- JobScheduler policy: PASS.
- Device-lab APK verifier: PASS for the four APK set.
- Historical structural gate: `BrokerActivityRuntime` is `412` lines versus the unchanged `330`
  threshold; retained as pre-existing P2 and not weakened.

## M3 short gate

Evidence: `D:\controlled-android-sandbox-evidence\T54-R02-M3-SHORT-20260812-231000-10x`

- Loops: `10/10`
- Commands: `128/128 PASS`
- Fixture32 Companion probes: `64` successful
- Target FATAL/ANR: `0`
- Teardown: `PASS`

## M3 formal gate

Evidence: `D:\controlled-android-sandbox-evidence\T54-R02-M3-FORMAL-20260812-commit-4d143402-run`

- Fixture64 user 0/user 1: PASS
- Fixture32 user 0/user 1: PASS; Companion32 probes `8`
- Command results: `18/18 PASS`
- Simultaneous Guest slots and task switching: PASS
- Stability: `1200/1200` seconds
- Target FATAL/ANR: `0`
- Teardown: `PASS`
- Formal evidence status: `PASS`

## Quark regression

After formal M3 PASS, Quark was launched and stopped three times without reinstalling it:

- Launch: `3/3 PASS`
- Stop: `3/3 PASS` via the corresponding force-stop cleanup
- Top Activity: `com.ucpro.BrowserActivity` on all three launches
- Target FATAL/ANR: `0`

Evidence: `D:\controlled-android-sandbox-evidence\T54-R02-RUNTIME-SMOKE-20260812-commit-4d143402`

## DingTalk UI smoke

After formal M3 PASS, the requested one-time UI smoke launched the installed DingTalk package:

- Route: `com.alibaba.android.rimet/.biz.LaunchHomeActivity`
- Result: **PASS (UI smoke)**
- Top Activity: `com.alibaba.android.rimet/.PrivacyPolicyActivity`, PID `5689`
- UI XML: captured; privacy container and agreement buttons were present
- Screenshot: captured at `D:\controlled-android-sandbox-evidence\T54-R02-RUNTIME-SMOKE-20260812-commit-4d143402\dingtalk-screen.png`
- FATAL/ANR: `0`

This is a launch/UI smoke only; it does not claim a logged-in DingTalk business-session acceptance.

## API32 product shell / F2-F5 basic smoke

On RD/API32, Flash2 `MainActivity` launched successfully. The Instance Settings activity opened
for the retained Quark record for all four sections: Location/F2, Camera/F3, Device/F4 and
Network/Cell/F5; UI XML was captured for each. Service, receiver, provider and JobScheduler coverage
is represented by the passing component-suite results in the formal M3 evidence.

## HyperOS/API36

`REAL_DEVICE_VERIFICATION_PENDING`. This task did not enter API 36 or process HyperOS.

## Git handoff

Final push and clean-tree verification are the remaining handoff actions for this report. No merge
to main, amend, rebase, squash, force-push, source ZIP, or Git bundle is part of T54-R02.
