# T54 final review

> Historical checkpoint only. This file records the pre-R02/R03/R04 state and is retained for audit history. It is superseded by `T54_FINAL_GLOBAL_REVIEW.md` and `T54_FINAL_CAPABILITY_MATRIX.md`; its historical `NOT PASS`/`not frozen` statements are not the final T54 status.

Review date: 2026-08-12  
Branch: `feature/ui-oem-compat`  
T53 baseline commit: `3a8c998ffd58dcb158f548df64d8d80590cf338c`  
T53 baseline tree: `ca1b800f50e2ebf745226e8200ad8714ee27b081`

## T54-R02 superseding addendum

The original sections below preserve the historical T54-R01/SX review record. The current R02
execution target and disposition are superseded by
[T54_R02_FINAL_REPORT.md](D:/github/controlled-android-sandbox/docs/oem-compat/T54_R02_FINAL_REPORT.md):

- Target: exact MuMu instance `RD测试`, index `1`, dynamically resolved session serial
  `127.0.0.1:16416`, Redmi `22041211A`, Android 12/API 32.
- Quark `com.quark.browser` was retained in place and not reinstalled.
- DingTalk was reinstalled once from `C:\Users\wangding\Downloads\dingding.apk`, version `7.8.10`
  / code `1178`.
- The generic Stub Activity window ownership fix closed the RD reproduction.
- M3 short gate: `10/10` PASS; formal M3: `1200/1200` seconds, `18/18` commands PASS,
  FATAL/ANR `0`, teardown PASS.
- Post-M3 Quark regression: launch/stop `3/3` PASS; DingTalk UI smoke reached
  `PrivacyPolicyActivity` with FATAL/ANR `0`.
- HyperOS/API36 remains `REAL_DEVICE_VERIFICATION_PENDING`.

## Disposition

T54 source and product UI work is implemented on the current Sandbox architecture. The post-restart
SX测试 resolution selected MuMu index `0` (`MuMuPlayer-12.0-0`) and, for this execution session only,
used the resolved serial `127.0.0.1:16384`. The runtime is Samsung SM-A5260, API 32 / Android 12;
the serial is connection data, not the instance identity. Xiaomi HyperOS / Android 16 / API 36
remains `REAL_DEVICE_VERIFICATION_PENDING`.

This review does not freeze T54 as a runtime PASS: the formal M3 gate remains open because the
MuMu fixture matrix reproduced target `StubActivity6/7` `WindowManagerGlobal.updateViewLayout`
FATAL exceptions and did not complete the required 20-minute zero-FATAL/ANR stability window.

One pre-existing structural gate remains open: `check-activity-task-virtualization.py` reports
`BrokerActivityRuntime` at 412 lines against its historical 330-line threshold. The T54 change did
not modify that class; the threshold was not weakened. This is a follow-up architecture item, not a
reason to fabricate OEM compatibility evidence.

## Delivered product surface

| Surface | Result |
| --- | --- |
| Home | Product cards for Sandbox apps, location, camera, device and network/cell; no Runtime/M3/bridge debug panel |
| Apps | APK import, refresh, per-instance Start, Instance Settings, Clone, Clear and Delete actions |
| Instance Settings | F2 Location, F3 Camera, F4 Device, F5 Network/Cell and DingTalk tabs |
| F2 Location | Static/blocked provider, coordinate, altitude, accuracy, speed, bearing, interval and trajectory profile; map picker is disabled as `NOT_IMPLEMENTED` |
| F3 Camera | IMAGE/VIDEO media import, instance-owned media metadata, clear and save through Peripheral Profile; Camera1/Camera2 are generic runtime behavior, not fake UI switches |
| F4 Device | Android ID, Brand, Model, Manufacturer, Serial, IMEI, MEID, SIM, IMSI, ICCID and operator; random generation and scoped reset |
| F5 Network/Cell | Wi-Fi SSID/BSSID/MAC, scan profile, MCC/MNC/LAC/CID, connectivity profile and scoped reset |
| DingTalk | Exact package/version gate, default OFF, manager-backed toggle; unsupported revisions explicitly show `NOT_IMPLEMENTED` |
| Developer Diagnostics | Moved from normal Home to Me; includes Runtime/status/maintenance/profile and component smoke diagnostics |

All editable controls write through `SandboxApplicationLayer` and `SxSandboxAdapter` to existing
typed contracts. F2/F5 reset actions are scoped and do not silently reset unrelated device fields.

## Compatibility closure

The T54 generic Android fix adds a typed `QUERY_ACTIVITY_ROOT` ActivityTask operation. For
`getTaskForActivity(token, onlyRoot=true)`, the Guest interceptor now queries the virtual task
ledger and returns the task id only for the root Activity; non-root Activities return `-1`. The
ledger self-test covers both cases.

The compatibility matrix and real-device plan are in:

- `ANDROID_OEM_COMPAT_MATRIX.md`
- `SX_REAL_DEVICE_COMPAT_BASELINE.md`
- `REAL_DEVICE_TEST_PLAN.md`

Historical SX Runtime/Hook/BlackBox/Xposed code was used only as audit evidence. No legacy runtime,
hook, BlackBox, LSPosed or package-specific Core branch was copied.

## Verification record

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | PASS |
| `:sandbox-contract:compileDebugJavaWithJavac` | PASS |
| `:sandbox-framework:compileDebugJavaWithJavac` | PASS |
| `:sandbox-runtime:compileDebugJavaWithJavac` | PASS |
| `ActivityTaskLedgerSelfTest` including T54 root query | PASS |
| `scripts/check-t54-android-compat.py` | PASS |
| Broadcast, JobScheduler, Package Service and Guest boundary gates | PASS |
| Full `scripts/verify-all.sh` | Not completed: WSL/Windows-mounted `reference_sources.py verify` exceeded the 124-second tool window and was terminated; no failure output was produced before that stage |
| Activity/Task structural gate | Existing baseline follow-up: 412 lines vs 330 threshold |
| SX测试 one-time resolution | PASS: index `0`, manager `adb_port=16384`, Samsung SM-A5260, Android 12 / API 32, Android ID `6af8fde7af55c9b2`; serial held fixed only for this session |
| Old SX evidence/uninstall | PASS: APK, package state and screenshot captured before uninstall; `com.sx.app.debug` then removed only on `127.0.0.1:16384` |
| Flash2 product UI smoke | PASS: Home, Apps, Instance Settings F2-F5/DingTalk, Me and Developer Diagnostics screenshots/XML retained |
| Generic component smoke | PASS: service start, broadcast delivery, provider readiness and service stop retained in `ui-component-smoke-result.xml` |
| Targeted M3 command matrix | Command results for fixture64/fixture32 × user0/user1 import-prepare, component-suite and launch were PASS; formal gate NOT PASS because target `StubActivity6/7` repeatedly raised `View=DecorView[...] not attached to window manager`, and no 20-minute stability run was completed. Evidence: `T54-R01-M3-DIAG-FIX10-20260812-2130` |
| Generic Stub window repair | Source/compile/unit checks PASS; the fix repairs detached ActivityClientRecord windows and preserves the real forward-navigation state, but the MuMu matrix still reproduces the OEM lifecycle failure under repeated multi-task pressure |
| DingTalk product UI flow | NOT PASS / not frozen: no claim of 5/5 launch and 5/5 stop; business session remains `REAL_USER_SESSION_REQUIRED` |
| Xiaomi HyperOS / Android 16 / API 36 | `REAL_DEVICE_VERIFICATION_PENDING` |

Historical device evidence is retained under:

```text
D:\controlled-android-sandbox-evidence\T54-REAL-DEVICE-20260812-134138\
```

The T54-R01 M3 diagnostic evidence is retained under:

```text
D:\controlled-android-sandbox-evidence\T54-R01-M3-DIAG-FIX10-20260812-2130\
```

The `127.0.0.1:16416` device observed concurrently was `RD测试` and was not used as SX测试
evidence. All current-session installation, uninstall, UI and runtime commands used only the
one-time resolved `127.0.0.1:16384` serial.

## Commit sequence

The required T54 commits are present without amend, rebase, squash or force-push:

1. `bffbef44 docs: inventory legacy sx product ui`
2. `b5724d6c feat: rebuild legacy sx ui on current sandbox architecture`
3. `e9504ae2 test: add android oem compatibility baseline`
4. `b95def38 fix: close android compatibility gaps`
5. `bf9dcbc1 docs: add android oem compatibility matrix`
6. This review commit: `docs: finalize T54 review`

Additional narrow follow-up commits preserve scoped profile reset semantics and align stale boundary
gates with the current application-layer architecture. The M3 runner also carries the explicit
Native Guest trust decision and deterministic command cleanup; these changes do not weaken the
runtime trust policy or rewrite history.

## Final device rule

Future MuMu operations must exact-match `SX测试` and resolve its current ADB serial before the
operation. The current Samsung API32 emulator cannot substitute for Xiaomi HyperOS Android 16
evidence, and the M3 formal gate must not be marked PASS until the target FATAL/ANR issue and the
20-minute stability requirement are both closed.
