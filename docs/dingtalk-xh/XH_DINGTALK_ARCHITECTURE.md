# XH / DingTalk architecture audit

T53 audit date: 2026-08-12. Reference package: `com.alibaba.android.rimet`, observed installed version `7.8.10` / versionCode `1178`.

## Evidence discipline

- **SOURCE** means the local XH restore under `D:\github\all_project\xh` or Controlled Android Sandbox source.
- **DECOMPILED** means smali or class inventory extracted from the installed SX host APK pulled from `127.0.0.1:16384`. It is not treated as the protected XH business implementation.
- **RUNTIME_OBSERVED** means an actual saved device trace. The SX launch trace initializes BlackBox and service proxies, but does not prove a DingTalk guest camera/location transaction.
- **INFERENCE** is explicitly labelled and is not used as proof of a capability.

## Located V(D)ing implementation

The exact decompiled names are:

- **DECOMPILED:** `com.lody.virtual.client.ipc.VDingManager`.
- **DECOMPILED:** `com.lody.virtual.remote.VDingConfig`.

`VDingManager` is a process-local singleton. Its constants are `GLOBAL_PACKAGE = com.alibaba.android.rimet` and `GLOBAL_USERID = 0xd9038` (889,912). It exposes current-app, per-user/package, and global get/set methods. Every getter returns a new default `VDingConfig` or `true`; every setter is a no-op in the recovered smali. No `apkanalyzer dex reference-tree` caller was found for the class in the pulled SX APK.

`VDingConfig` is a `Parcelable` with fields `a` through `o`: seven-plus string fields, a string list `e`, boolean `f`, and integer fields `n`/`o`; `f24769p = 6`. The constructor defaults `e` to an empty list, `f = true`, `n = 1`, `o = 0`. Field meanings are **not proven** by the protected XH source or runtime and are intentionally not guessed.

## Call graph and ownership

```text
SX host startup
  -> BlackBoxSandboxEngine / BlackBoxCore
  -> HookManager service proxies
       -> ILocationManagerProxy
       -> IWifiManagerProxy / IWifiScannerProxy
       -> ITelephonyManagerProxy / IPhoneSubInfoProxy
       -> IDeviceIdentifiersPolicyProxy
       -> IConnectivityManagerProxy
       -> IActivityManagerProxy / IActivityTaskManagerProxy
       -> media.camera binding
  -> package/class-loader initialization
  -> DingTalkHook package gate (decompiled helper surface)
       -> legacy privacy/exported/activity/process helpers
       -> no proven VDingManager caller
```

The decompiled `DingTalkHook` contains a package literal and helper methods for privacy preferences, exported-activity handling, class-loader setup, service blocking, and process-exit handling. Its `install` entry is a framework-level package gate; saved runtime evidence only shows the BlackBox service-proxy layer. No trace is evidence that a DingTalk guest reached a real DingTalk camera or location flow.

## Method-level mapping

| XH method / surface | Dependency | Function | Classification | Controlled Sandbox target |
|---|---|---|---|---|
| `VDingManager.get*DkConfig` | `VDingConfig` | Compatibility configuration API | Specific compatibility control plane; semantics unproven | Isolated `DingTalkCompatibilityManager`, default-off, no core hook |
| `VDingManager.set*` | none in recovered implementation | No-op setters | Specific legacy stub | Not copied; generic profile authority owns state |
| `DingTalkHook.hookServiceManager` | Binder service registry | Legacy service filtering | Specific/legacy | Generic service policy and explicit capability contracts |
| `DingTalkHook.hookActivityThread` | ActivityThread / LoadedApk | Runtime identity/class-loader manipulation | Specific/legacy and high risk | Existing generic identity/runtime layer; no DingTalk condition in Core |
| `DingTalkHook.hookPrivacyPreferences` | app shared preferences | Preseed privacy state | Specific behavior | No automatic preseed; explicit user-controlled flow |
| `DingTalkHook.hookExportedActivityUtils` | Activity/ATMS | Bypass exported checks | Specific/unsafe | Correct manifest/ATMS semantics only |
| `DingTalkHook.hookSystemExit` / process kill | Process lifecycle | Suppress termination | Unsafe legacy workaround | Never implemented |
| `LocationHook` | `Location`, `LocationManager` | Fixed location getters and last-known location | General capability | `VirtualLocationProfile` + Location Binder projection/callbacks |
| `CameraHook` | Camera1/Camera2/Image/MediaMetadataRetriever | Preview/capture source substitution | General capability | `VirtualCameraProfile` + source store + capture engine |
| `DeviceHook` | Build/Telephony/Settings | Device identity projection | General capability | `VirtualDeviceServiceProfile` |
| `NetworkHook` | WifiInfo/WifiManager | Wi-Fi projection | General capability | `VirtualWifiProfile` |
| referenced `CellHook` | Telephony/cell | Cell environment projection | General capability | `VirtualCellInfoSnapshot` + telephony profile |

## Boundary decision

The DingTalk layer is restricted to revision identification, explicit enable/disable state, diagnostics, and profile orchestration. Location, camera, media source, identity, Wi-Fi and cell behavior are generic services. `WHY_NOT_GENERAL`: only the exact DingTalk package/version gate and compatibility diagnostics have no stable Android-wide meaning; the data-plane behavior does.

