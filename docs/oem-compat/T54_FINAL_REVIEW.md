# T54 final review

Review date: 2026-08-12  
Branch: `feature/ui-oem-compat`  
T53 baseline commit: `3a8c998ffd58dcb158f548df64d8d80590cf338c`  
T53 baseline tree: `ca1b800f50e2ebf745226e8200ad8714ee27b081`

## Disposition

T54 source and product UI work is implemented on the current Sandbox architecture. Xiaomi
HyperOS / Android 16 / API 36 runtime evidence is not available and remains
`REAL_DEVICE_VERIFICATION_PENDING`. No real-device PASS is claimed.

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
| Approved ADB serial `127.0.0.1:16384` | `device not found` |
| Old SX evidence/uninstall | Not run because the approved device was absent; no alternate serial was used |
| Xiaomi HyperOS / Android 16 / API 36 | `REAL_DEVICE_VERIFICATION_PENDING` |

The exact device preflight attempted only:

```text
adb -s 127.0.0.1:16384 ...
error: device '127.0.0.1:16384' not found
```

## Commit sequence

The required T54 commits are present without amend, rebase, squash or force-push:

1. `bffbef44 docs: inventory legacy sx product ui`
2. `b5724d6c feat: rebuild legacy sx ui on current sandbox architecture`
3. `e9504ae2 test: add android oem compatibility baseline`
4. `b95def38 fix: close android compatibility gaps`
5. `bf9dcbc1 docs: add android oem compatibility matrix`
6. This review commit: `docs: finalize T54 review`

Additional narrow follow-up commits preserve scoped profile reset semantics and align stale boundary
gates with the current application-layer architecture. They do not change the requested T54 commit
meaning or rewrite history.

## Final device rule

The T54 OEM status may only move to `PASS_RUNTIME` after the approved Xiaomi HyperOS device is
available and the evidence plan is executed with serial `127.0.0.1:16384`. Emulator, API32, source
compile, or another ADB serial cannot substitute for that result.
