# T54-R03 final report

Date: 2026-08-12
Branch: `feature/ui-oem-compat`
Result: **PASS_WITH_ENVIRONMENT_LIMITATIONS**

The two acceptance tracks are reported independently. Track B generic API36 validation is closed
for the tested 64-bit fixture path. Track A DingTalk formal 5/5 closure is **not** claimed because
the MuMu session contained external test-runner interference that invalidated part of the product-UI
round matrix.

## Track A — RD / Android 12 API32 / DingTalk

### Dynamic device and app baseline

The RD target was resolved by `scripts/mumu_instance.py --instance-name RD测试`; the serial was
taken from that resolution, not guessed from historical port data.

| Field | Result |
|---|---|
| Project / instance | Controlled Android Sandbox / 闪现2 / `RD测试` |
| Index / instance id | `1` / `ginstance1400582734232539401` |
| VM / state | `MuMuPlayer-12.0-1` / `start_finished` |
| Resolved serial | `127.0.0.1:16416` |
| Device | Redmi `22041211A` |
| Android / API | 12 / 32 |
| Android ID | `398eea33120cd887` |
| `adb get-state` | `device` |

DingTalk was checked in place and remained `com.alibaba.android.rimet`, version `7.8.10`,
versionCode `1178`. The source APK at `C:\Users\wangding\Downloads\dingding.apk` has the
required SHA-256 `2A1E50C004D97530CDAD65D65BCA87E06F8AA031072EC29574E5DE7971D320BB`.
Quark `com.quark.browser` was retained and not cleared or uninstalled.

### Product UI evidence

The formal Flash2 product route was used: Home → Apps → Import App → explicit Native Guest trust
dialog → DingTalk instance → Launch/Stop. Home and import evidence show the Flash2 product shell and
not `DebugCommandActivity`. The generic trusted-native import path and Android 12 accessibility
interaction-connection boundary were fixed in commit `1a78466f`.

The import succeeded and the Apps catalog displayed DingTalk `7.8.10` / `com.alibaba.android.rimet`.
The first launch before the accessibility fix produced a real
`VIRTUAL_ACCESSIBILITY_MUTATION_DENIED:addAccessibilityInteractionConnection` failure; it was
fixed and not hidden. Subsequent valid DingTalk logs reached Guest generation 2
`PrivacyPolicyActivity` with target FATAL/ANR count zero. DingTalk’s own
`checkExportedActivityStartup` calls `System.exit(0)` as part of its launch handoff; this is recorded
as an expected app exit followed by Guest recovery, not reclassified as a Sandbox FATAL.

The preserved `formal-5x/formal-5x-summary.json` contains four usable DingTalk launch logs with
generation-2 Activity creation and no target FATAL/ANR. One round contains only an unrelated
`com.cost.app` runner invocation. Stop evidence is also mixed: external runner activity and a
Flash2 crash dialog appeared during later rounds, and the Apps catalog was subsequently lost by the
interfering environment. Therefore the required Track A gate is recorded as:

| Gate | Result |
|---|---|
| Dynamic RD resolution | PASS |
| Formal product UI import | PASS |
| DingTalk 7.8.10 / 1178 | PASS |
| Launch | 4 usable DingTalk logs; formal 5/5 **NOT CLOSED** |
| Stop | partial raw evidence; formal 5/5 **NOT CLOSED** |
| Target FATAL / ANR in usable logs | 0 / 0 |
| Logged-in business session | `REAL_USER_SESSION_REQUIRED` |

Raw evidence is under `build/t54-r03-evidence/track-a/`, including the original 5-round records.
The API32 product-shell regression snapshot after the code changes still opens Flash2 Home and
shows the normal Home/Apps/Me navigation.

### Track A boundary

No DingTalk package-name branch was added to the core Runtime. DingTalk-specific compatibility
manager behavior remains outside the generic Activity/Task, Package, accessibility, and native-trust
boundaries. No logged-in business, camera business, or location business PASS is claimed.

## Track B — Pixel Android 16 / API36 AVD

### Target resolution

The target was dynamically matched to `Pixel_Android16_API36_GoogleApis_x86_64`; the resolved serial
was `emulator-5554`. Observed release/API/ABI were Android 16 / API 36 / primary `x86_64`, with
`x86_64,arm64-v8a` advertised. Full resolution evidence is in
[PIXEL_API36_AVD_RESOLUTION.md](../runtime/PIXEL_API36_AVD_RESOLUTION.md) and
`build/t54-r03-evidence/track-b/pixel-api36-resolution.json`.

### Build and ABI inventory

All current artifacts were rebuilt from this checkout before installation. Gradle build completed
successfully for contract, framework, runtime, native, companion, app, and fixture modules.

| Artifact | SHA-256 | Native ABI | API36 classification |
|---|---|---|---|
| `app-debug.apk` | `8E4BC79C96B5347B51DE697FE91D4C59AF6955356FEE7EF187A3F2226FEE990C` | arm64-v8a, x86_64 | supported |
| `fixture-basic-debug.apk` | `D245444FD57463B746C192CA78EAE05503B717BFE66F30FC701E2524F636286A` | arm64-v8a, x86_64 | supported |
| `fixture-compat32-debug.apk` | `46CB929512A7BFE526076E97F5B36B89957DD08C5A644594784C961A6DC68448` | armeabi-v7a, x86 | `ABI_UNSUPPORTED_ON_X86_64_AVD` |
| `sandbox-companion32-debug.apk` | `9589EC816A5142862A957B44F4CB3B0C615997FB581C812544E9EE617013ECC5` | armeabi-v7a, x86 | `ABI_UNSUPPORTED_ON_X86_64_AVD` |

The unsupported 32-bit classification is an artifact/ABI limitation, not an Android 16 framework
failure. It was not converted into a fake PASS.

### Generic Runtime results

The rebuilt Flash2 host installed and launched on the Pixel AVD through `MainActivity`. Using the
64-bit `fixture-basic` path, the following diagnostic operations returned PASS for virtual users 0
and 1 where applicable:

- Package import / native trust / prepare;
- Activity launch, relaunch, task switch, detail Activity and stop;
- Service start/stop, Receiver delivery and Provider preparation through `component-suite`;
- JobScheduler source/bridge and ownership checks PASS; no direct Guest JobService callback was
  claimed as a device result because the existing fixture command surface does not schedule a Job;
- F2 static location profile, reset, and separate user profiles;
- F3 camera profile configuration and Camera2 service binding/enumeration/preview-texture path;
- F4 device profile read/write/reset;
- F5 virtual telephony/Wi-Fi framework probe, with physical identifiers denied by contract;
- Product Home UI and normal navigation;
- 5-minute stability sample: 12 cycles, 36 launch/stop samples, 36/36 PASS, target Sandbox FATAL/ANR 0.

The raw command results and logs are under `build/t54-r03-evidence/track-b/`. The Job result is
therefore `SOURCE_BRIDGE_PASS_DEVICE_CALLBACK_NOT_RUN`, not a fabricated device PASS.

### Android 16 compatibility fix

Camera1 on API36 initially exposed a general Activity/Task boundary problem: Android 16’s
`CameraManager` path invoked `ActivityManager.getAppTasks()` with the Host op-package name while
running inside a Guest. The existing strict check raised `VIRTUAL_APP_TASK_PACKAGE_MISMATCH`.
The generic fix passes the Host package alias into the Guest Activity/Task interceptor only for
virtual handling; the response still comes from the Guest task ledger and is never passed through to
the Host ATMS. The self-test now covers the alias, the rebuilt API36 runtime no longer produces the
mismatch, and Activity/task/Camera2 regression passed.

### F3 and environment limitation

Camera2 framework setup is healthy: the virtual camera service bound, enumerated one camera, and
created a valid preview texture. The Camera1 probe no longer hits the task-package mismatch, but the
API36 AVD cannot connect the legacy camera service and the native probe cannot resolve the platform
Camera1 symbols from its isolated namespace. This is recorded as `AVD_CAMERA_HAL_LIMITATION` for
the legacy Camera1 capture path; no Runtime FATAL/ANR occurred. A physical OEM Camera HAL was not
used to replace this evidence.

F5 and device identity probes preserve the isolation contract: Android ID and virtual device fields
are returned, while IMEI/IMSI/ICCID and other protected physical identifiers can return
`SecurityException`. This is expected policy behavior, not a bypass requirement.

### Optional third-party smoke

- Quark on API36: `NOT_RUN_NO_LOCAL_APK`; the task did not copy or reinstall the RD device’s Quark.
- DingTalk on API36: `NOT_RUN`; Track B generic Runtime evidence does not depend on an ABI-incompatible
  third-party APK and does not replace Track A.

## Static checks and known baseline

Passed checks include T54 Android compatibility, package-service boundary, native trust boundary,
policy/accessibility boundary, JobService bridge, JobScheduler policy, and Notification/Job lifecycle.
`check-activity-task-virtualization.py` retains the historical P2 warning that `BrokerActivityRuntime`
is 412 lines versus the 330-line structural threshold; this was not introduced or weakened by T54-R03.
The static Android compiler has a separate known platform-stub baseline and was not weakened to make
API36 pass.

## Explicit non-scope and final Git

`Xiaomi HyperOS / Android 16 / API36 = REAL_DEVICE_VERIFICATION_PENDING`. The Pixel AVD result does
not claim HyperOS, Xiaomi Camera HAL, OEM AppOps/background policy, physical SIM/RIL, real Wi-Fi/Cell,
or ARM64 OEM behavior.

No merge to main, F6, source ZIP, Git bundle, force push, or unrelated feature was performed.
