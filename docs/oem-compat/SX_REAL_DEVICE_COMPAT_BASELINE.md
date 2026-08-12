# SX real-device compatibility baseline

Baseline date: 2026-08-12  
Historical source: `D:\github\all_project\sx\docs\crash-analysis\real-device-issues-log.md`  
Current architecture baseline: T53 `3a8c998ffd58dcb158f548df64d8d80590cf338c`  
Target real device: Xiaomi / HyperOS / Android 16 / API 36

This document translates the historical SX report into current Sandbox work items. Historical
SX hooks and BlackBox code are evidence only; they are not migration targets.

## Status vocabulary

- `ALREADY_COVERED`: the current generic architecture has source and regression evidence for the
  behavior.
- `PARTIALLY_COVERED`: the generic contract exists, but one or more runtime or vendor surfaces
  still need a bounded verification.
- `MISSING`: no safe current implementation or contract exists.
- `ARCHITECTURE_NOT_APPLICABLE`: the old SX mechanism is not part of Flash 2's architecture.
- `REAL_DEVICE_ONLY`: the result depends on hardware, OEM policy, or a vendor HAL.
- `REAL_DEVICE_VERIFICATION_PENDING`: the required Xiaomi/HyperOS device evidence is not present.

## Historical issue mapping

| ID | Android/OEM | Historical SX issue | Current root cause | Flash 2 implementation / boundary | Status | Verification |
| --- | --- | --- | --- | --- | --- | --- |
| SX-01 | LSPosed / Xposed | SX reported an inactive module | Flash 2 is an application plus generic Sandbox runtime, not an Xposed module | No Xposed activation gate is copied; no fake “module loaded” result is shown | ARCHITECTURE_NOT_APPLICABLE | Source audit; product UI does not expose LSPosed |
| SX-02 | Android 12+ | `LaunchActivityItem` did not receive the Guest `ActivityInfo` / `Intent` projection | Host transaction and Guest route/generation were not updated together | Generic Activity route, transaction owner, `ActivityInfo`, `Intent`, and generation are carried through the Sandbox runtime | ALREADY_COVERED | Activity route self-tests; API32 Stage A |
| SX-03 | API 31+ | `isTaskRoot` / `getTaskForActivity(..., onlyRoot=true)` caused a proxy to finish or reopen | Host task token was confused with Guest task identity | `ActivityTaskFrameworkInterceptor` and ActivityClient projection use the Guest task ledger | ALREADY_COVERED | Activity task self-tests; API32 Stage A |
| SX-04 | Android 14+ | `bindServiceInstance` was not routed to the virtual service instance | Newer service binding carries caller/package/UID and instance identity | Generic service routing projects caller identity and routes through the Broker service boundary | ALREADY_COVERED | Service route self-tests; Quark/DingTalk lifecycle evidence |
| SX-05 | Android 12–16 | PM typed/long flags and package/component projection drifted | Public API overloads changed while virtual metadata remained one logical contract | Package Manager invocation handler normalizes long flags and preserves bounded package/component metadata | ALREADY_COVERED | Package Manager contract tests; compile source audit |
| SX-06 | Android 13+ | Dynamic/manifest Receiver exported state was ambiguous | Receiver registration flags must be projected without widening authority | Receiver flags are typed in the contract and routed through receiver lifecycle coordinators | ALREADY_COVERED | Broadcast model checks; receiver self-tests |
| SX-07 | Android 12–16 | Provider caller UID / package projection could leak host identity | Provider transport needs scoped caller and session ownership | Provider broker and Guest provider transport keep package/user/session ownership in the generic boundary | ALREADY_COVERED | Provider contract tests; Stage A component suite |
| SX-08 | Android 12–16 | JobScheduler callbacks lacked typed lifecycle ownership | Job start/stop/finish must be one bounded execution | Typed JobScheduler contract, death registration, and Guest callback bridge are in the current runtime | ALREADY_COVERED | JobScheduler policy checks and self-tests |
| SX-09 | Android 16 / HyperOS | `com.android.deskclock` Activity transition / task-root / service handoff | Vendor task and service behavior still needs a device run even after generic fixes | `HYPEROS_REGRESSION_CASE_01` exercises import, prepare, launch, task root, Activity transition, service, provider, relaunch and stop | REAL_DEVICE_VERIFICATION_PENDING | Xiaomi HyperOS plan; only real-device evidence can close |
| SX-10 | MuMu / camera HAL | Camera1 native connect/callback/capture/reopen and Camera2 media paths | Native and Binder boundaries differ by device/HAL | Generic Camera1 native boundary and Camera2 profile/media contract are already implemented; UI only configures the shared profile | PARTIALLY_COVERED | PASS on T53 MuMu API32; vendor/HAL verification pending |
| SX-11 | MuMu / vendor policy | `checkAudioOperation` shutter-audio attribution warning | Emulator/vendor AppOps policy, not camera data-plane failure | Warning is retained as evidence; no warning suppression or package-specific bypass | REAL_DEVICE_ONLY | Record logcat on each target device |
| SX-12 | Android 16 / vendor window | SurfaceView callback was absent while SurfaceTexture passed | Host virtual window / vendor Surface implementation boundary | No DingTalk-specific workaround; use the generic window contract and record vendor results | REAL_DEVICE_ONLY | Camera/window test on Xiaomi HyperOS |
| SX-13 | DingTalk 7.8.10/1178 | Privacy/exported/System.exit handoff was historically bypassed by hooks | Legacy SX altered app-owned behavior instead of repairing generic Activity recovery | `DingTalkCompatibilityManager` is default-off and only orchestrates generic Profiles; no hook/dex/exported/process-exit mutation | ALREADY_COVERED | T53 10/10 launch and stop; real user pages remain gated |

## Current compatibility decision

The current source baseline has no known generic Android 12–16 contract gap that is safe to
“patch by guess”. The remaining T54 compatibility work is therefore:

1. retain the generic Activity, Service, Receiver, Provider, JobScheduler, Camera1 and Camera2
   boundaries already covered by T53;
2. add explicit source/contract checks so future API changes cannot silently fall back to an old
   overload or host identity;
3. execute `HYPEROS_REGRESSION_CASE_01` on a real Xiaomi HyperOS Android 16 device;
4. record all unverified OEM/HAL results as `REAL_DEVICE_VERIFICATION_PENDING`, never as PASS.

No Xiaomi package hack, Xposed hook, BlackBox migration, or DingTalk-specific Core branch is
introduced by this baseline.
