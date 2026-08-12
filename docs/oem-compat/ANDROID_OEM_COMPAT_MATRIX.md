# Android / OEM compatibility matrix

T54 target: Xiaomi HyperOS, Android 16, API 36  
Current branch: `feature/ui-oem-compat`  
T53 source baseline: `3a8c998ffd58dcb158f548df64d8d80590cf338c`

This matrix separates generic contract evidence from hardware/OEM evidence. A source-level or
emulator result never closes a Xiaomi HyperOS item. For this project, MuMu identity is the exact
instance name `SX测试`; its ADB serial is dynamically resolved from the current MuMu instance
list before each operation. The current resolution is recorded in
`docs/runtime/SX_TEST_DEVICE_RESOLUTION.md`.

## Status vocabulary

| Status | Meaning |
| --- | --- |
| `PASS_CONTRACT` | Current source contract and self-test cover the behavior. |
| `PASS_RUNTIME` | The behavior passed on the approved target runtime/device. |
| `PARTIALLY_COVERED` | Generic path exists, but a runtime, vendor, or HAL boundary remains. |
| `REAL_DEVICE_VERIFICATION_PENDING` | Xiaomi HyperOS/API36 evidence is not available yet. |
| `ARCHITECTURE_NOT_APPLICABLE` | The historical SX mechanism is not part of Flash 2. |
| `NOT_IMPLEMENTED` | The product surface is intentionally not wired; UI labels it explicitly. |

## Matrix

| Area | Android/OEM pressure point | Current Flash 2 implementation | Current evidence | T54 status |
| --- | --- | --- | --- | --- |
| Activity launch | Android 12+ `LaunchActivityItem`, `ActivityInfo`, `Intent`, generation | Typed Activity route, transaction owner and generation in the generic runtime | Activity route and API32 stage evidence from T53 | `PASS_CONTRACT` |
| Task root | API31+ `getTaskForActivity(token, onlyRoot)` / `isTaskRoot` | `ActivityTaskFrameworkInterceptor` queries the virtual ledger; root query returns taskId and non-root returns `-1` | `ActivityTaskLedgerSelfTest`, T54 root-query test | `PASS_CONTRACT` |
| Service binding | Android 14+ `bindServiceInstance` caller/instance identity | Generic service route and Broker-owned service session | Service route self-tests and T53 lifecycle evidence | `PASS_CONTRACT` |
| Package Manager | Long flags and changed overloads | Invocation handler normalizes typed/long flags and bounded metadata | Package Manager contract/source guard | `PASS_CONTRACT` |
| Receivers | Android 13+ exported/not-exported registration flags | Receiver contract carries exported state; router rejects ambiguous combinations | Receiver self-tests and source guard | `PASS_CONTRACT` |
| Providers | Caller package, virtual user and UID projection | Broker provider session scopes caller identity to package/user/session | Provider contract and Stage A component evidence | `PASS_CONTRACT` |
| JobScheduler | Callback ownership and start/stop/finish lifecycle | Typed JobScheduler contract, death registration and Guest callback bridge | JobScheduler policy checks/self-tests | `PASS_CONTRACT` |
| Camera1 | Native connect, callback, NV21/JPEG capture, release/reopen | Generic native Camera1 boundary | T53 MuMu API32 PASS; vendor/HAL still open | `PARTIALLY_COVERED` |
| Camera2 | Image/video/preview/capture and media ownership | Peripheral profile plus instance-owned media store | T53 media contract evidence; HyperOS camera run pending | `PARTIALLY_COVERED` |
| Surface/window | Vendor SurfaceView vs SurfaceTexture behavior | Generic window/surface contract; no app-specific workaround | Requires Xiaomi window run | `REAL_DEVICE_VERIFICATION_PENDING` |
| Audio/AppOps | Vendor shutter-audio attribution and policy | Warning is recorded; no policy bypass or suppression | Requires target-device logcat | `REAL_DEVICE_VERIFICATION_PENDING` |
| `com.android.deskclock` | Activity transition, task root, service/provider handoff | Generic runtime path only; no package-specific branch | HYPEROS regression cases 01–05 defined | `REAL_DEVICE_VERIFICATION_PENDING` |
| DingTalk 7.8.10 / 1178 | Privacy/exported/process-exit behavior | Default-off `DingTalkCompatibilityManager` orchestrates generic profiles only | T53 launch/stop evidence; business pages remain gated | `PARTIALLY_COVERED` |
| LSPosed/Xposed | Historical module activation state | Not an Xposed module; no activation result is exposed | Architecture/source audit | `ARCHITECTURE_NOT_APPLICABLE` |

## Product UI boundary

The Flash 2 product shell exposes Home, Apps and Me. Instance Settings contains F2 Location, F3
Camera, F4 Device, F5 Network/Cell and DingTalk compatibility. Developer Diagnostics is reached
from Me; normal Home does not expose Runtime/M3/bridge terminology. A control is only enabled when
it writes a current profile or calls a current service contract. Deliberately unavailable controls
are labeled `NOT_IMPLEMENTED`.

## Real-device close criteria

The target row can move from `REAL_DEVICE_VERIFICATION_PENDING` to `PASS_RUNTIME` only after all of
the following are retained under the T54 evidence root:

1. Dynamically resolve MuMu instance `SX测试`, run `adb -s <resolved-serial> get-state`, and read
   manufacturer/HyperOS/API properties through that same resolved serial;
2. old SX package evidence before any cleanup, followed by an old-package-only uninstall if needed;
3. DeskClock, fixture, Quark and DingTalk acceptance logs with zero unclassified FATAL/ANR;
4. UI screenshots at 320dp and 360dp, including F2–F5 and Developer Diagnostics;
5. camera/window/AppOps logcat for the device/HAL result;
6. package revision SHA-256 and the exact test timestamp/serial on every result.

No fixed ADB port is a long-term MuMu identity, and no other emulator or device may be silently
folded into the `SX测试` evidence. Xiaomi HyperOS/API36 remains
`REAL_DEVICE_VERIFICATION_PENDING` until a Xiaomi device is resolved and verified.
