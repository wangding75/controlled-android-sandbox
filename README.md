# Controlled Sandbox — Clean-room Development 0.3

A from-scratch Android application-virtualization research project. Production source is intentionally separated into domain, Binder contract, framework adapter, native policy, runtime, product app and test-fixture modules. No code from VirtualApp, NewBlackbox, Twoyi or their forks is included.

## Implemented in the current development snapshot

- Defensive single- and multi-APK import into app-private storage with staged Binder-owned install sessions, Base/Feature/Configuration Split validation, dependency ordering, per-artifact SHA-256, signer continuity and downgrade checks.
- Bounds-checked binary `AndroidManifest.xml` parsing for application, activities, services, receivers, providers, intent-filter priority/categories/data/MIME, authorities, permissions, process names and isolated services.
- One Binder-owned package authority in a dedicated process, with a typed PID/UID-bound management capability, atomic package/virtual-instance/policy catalog, legacy migration, immutable SHA-256 APK/native revision publication and orphan cleanup reporting.
- Multiple virtual users/instances with independent data roots and deterministic virtual UIDs.
- Versioned AIDL protocol, SHA-256-bound immutable APK revisions, explicit session state machine, eight process slots, retained Binder connections, death detection, generation recovery and one-time Activity route tokens.
- Guest `DexClassLoader` with host-internal deny rules, dependency-ordered Base/Split class and resource paths, split-aware `ApplicationInfo`/`Context`, instance-scoped storage redirection and host-unwrapping denial, `Application` bootstrap and custom atomic `SharedPreferences`.
- Broker-authoritative Activity, started/bound Service, dynamic Receiver, explicit/implicit manifest Receiver and Provider authority/CRUD/Call/Batch/Cursor/FileDescriptor routing, ordered-broadcast source policy, broker-owned ContentObserver callbacks, Session-bound TTL/one-time URI Grants and unified Provider resource lifecycle cleanup with Guest component bridges.
- Binder-issued, APK-revision-bound virtual package/component snapshot plus per-virtual-user permission, AppOps, package-enabled and component-enabled policy consumed by PackageManager, PermissionManager and bounded AppOps hooks.
- Typed virtual Intent filters and deterministic Guest-local PackageManager query/resolve for Activity, Service, Receiver and Provider metadata, including action/category/data/MIME, default-only, disabled-component, install-source and install-time semantics.
- Catalog-v5 runtime-permission requests and audit, a Runtime-Broker-only typed Binder capability, host-capability-backed effective grants, Activity callback bridging and same-generation permission/AppOps/camera-location service-gate refresh.
- Method-level Camera/Location and bounded AudioManager capture proxies, fail-closed proxy readiness, Attribution-aware AppOps, live cleanup of recognized capability resources and per-generation capability-call audit.
- Durable PendingIntent identity and Broker routing, plus a Package-Service-owned scoped Binder authority for cross-process Clipboard and basic Account state. Revision-scoped exact/repeating Alarm scheduling, offline PendingIntent recovery, Notification Channel/Group/foreground-service/interaction lifecycle and typed JobScheduler constraints, periodic/latency/deadline/expedited/persisted/backoff policy survive Package Service recreation. Android-version device validation remains pending. Receiver and Provider cleanup authorities are extracted from the central Runtime Broker.
- WebView data-directory suffix per virtual user/process slot.
- Native C++ Guest-library PLT/GOT interception with modern filesystem syscall confinement, virtual `/proc/self` identity files, controlled dynamic-library loading and host-side native tests. Android device/ABI evidence remains pending.
- Structured JSONL runtime diagnostics, uncaught-exception capture and main-thread liveness watchdog.
- A debug-only ADB command surface, comprehensive Fixture APK and strict 20-minute Emulator gate.
- Architecture-boundary checks and generated SBOM.

## Third-milestone status

**Not complete.** The repository intentionally refuses to create an M3 release ZIP unless a real Android build and Emulator evidence bundle passes `scripts/check-m3-release-gate.sh`.

The following remain device-gated or incomplete:

- Real Android Gradle Plugin/NDK build in the current execution environment.
- API-level validation of hidden/reflected framework fields and PackageManager Binder signatures.
- Device validation of bound services, dynamic receivers, Provider transport/query routing, URI grants and PendingIntent ownership.
- Declared remote-process and `isolatedProcess` routing.
- Activity task/back-stack fidelity across API levels.
- Android-version validation of Guest native-library interception, linker namespace behavior and modern syscall availability across all target ABIs.
- WebView renderer-process isolation evidence.
- A 20-minute zero-crash/zero-ANR Emulator report.

See `docs/M3_GATE.md` and `docs/TEST_REPORT.md`.

## Local verification

```bash
./scripts/verify-all.sh
```

This runs every gate available without Android SDK/Emulator access. It does **not** substitute for the device gate.

## Real Emulator gate on Windows

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\run-emulator-m3.ps1
```

The script builds and installs the host and Fixture APKs, runs two virtual instances, exercises components/JNI, performs the requested stability loop and writes evidence under `artifacts\m3-emulator-*`.

## Build prerequisites

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35.0.0 or newer
- Android NDK and CMake 3.22.1
- Gradle/Maven dependency access or a prepared cache

```bash
./gradlew clean check :fixture-basic:assembleDebug :app:assembleDebug
```

## License

Original project code is released under Apache License 2.0. Imported APKs retain their own licenses and distribution restrictions. See `LICENSE`, `NOTICE`, `THIRD_PARTY_LICENSES.md` and `docs/CLEAN_ROOM_POLICY.md`.

## Frozen baseline and reproducible build

The uploaded B3-T5A source is frozen by the `baseline-b3-t5a-upload` tag. Toolchain values and the uploaded archive identity are recorded in `build-environment.lock.json` and `docs/BASELINE.md`.

Host-only gates:

```bash
./scripts/verify-all.sh
```

Locked Android cache bootstrap on Windows:

```powershell
.\scripts\bootstrap-build-cache.ps1
```

Offline two-pass reproducibility check:

```powershell
.\scripts\reproducible-build.ps1 -VerifyTwice
```

The two-pass command builds unsigned release APKs twice with a clean task graph, no Gradle build cache and no parallel execution, then compares their bytes. It intentionally does not run an Emulator or physical-device test.

## M4-T13 source baseline

M4-T13 adds a typed Guest JobService execution bridge. Trusted Host Job callbacks are reduced to bounded `VirtualJobParametersSnapshot` data, Package Service owns the `SCHEDULED → DISPATCHING → RUNNING` state machine, and Guest `jobFinished` is a one-shot package/user/process/generation/dispatch-token capability. This is source/host evidence only; Android-version and OEM JobScheduler behavior remains device-gated.

## M4-T14 source baseline

M4-T14 moves Service ownership behind `RuntimeServiceCoordinator`. Started, bound and foreground state is generation-aware; bound clients may provide death-linked Binder tokens; stale start IDs cannot stop newer work; and sticky/redeliver Services are recreated after Guest process recovery. Foreground notification/type enforcement and Android/OEM lifecycle behavior remain device-gated.

## M4-T15 source baseline

M4-T15 makes the Broker-owned Activity/Task model recoverable, queryable and reachable from Guest `ActivityManager`/`ActivityTaskManager` calls. It adds bounded launch-flag/result semantics, package/user/revision-isolated running and recent task projections, local `IAppTask` Binder handles, owner-checked task mutations, and an atomic CRC-protected checkpoint that restores saved task state without reviving dead one-time route or result-delivery authority. System Recents visuals, Window/Transition integration and Android/OEM device compatibility remain open.

## M4-T16 source baseline

M4-T16 makes PendingIntent identity durable and revision-bound, including Activity Result, mutability, FillIn/ClipData, sender permissions, virtual creator identity and cross-generation reattachment. It adds typed exact/repeating Alarm state, Listener and PendingIntent delivery, offline retention, Package-Service recovery, Notification Channel/Group lifecycle, foreground-service mapping and persistent click/delete/action sender identity. JobScheduler now persists typed network/power/storage/idle constraints, periodic/latency/deadline/expedited/persisted policy and bounded linear/exponential retry state while retaining the trusted Host-to-Guest JobService bridge. Android AlarmManager/SystemUI/JobScheduler timing, quota and OEM behavior remain device-gated.


## M4-T17 B1 source baseline

M4-T17 B1 extends the Guest-only PLT/GOT hook set with `openat2`, `statx`, `renameat2`, `faccessat2`, `getdents64` and file-backed `mmap`. `/proc/self/maps`, `/proc/self/cmdline` and `/proc/self/status` are projected from virtual process identity without exposing Host private paths. `dlopen` and `android_dlopen_ext` are constrained to the Guest native-library root and an explicit public system-library allowlist. These are source and Host-native test results; Android linker, OEM and ABI behavior is not yet device-proven.


## M4-T17 B2 source baseline

M4-T17 B2 extends the Guest-only native policy to IPv4/IPv6 sockets, forward and reverse DNS, virtual hostname and bounded synthetic interface enumeration. Host network-interface identity is not returned to Guest native code. RECORD_AUDIO and AppOps decisions now configure a generation-bound native capture gate; AAudio and NDK MediaRecorder start/stop symbols are intercepted when present, and revocation clears active capture authority while the existing Binder capability lease registry releases Java/Binder audio resources. These are source and Host-native tests; Android audio-server, VPN, proxy and OEM network behavior remain device-gated.

## M4-T17 B3 source baseline

M4-T17 B3 adds an independent `sandbox-companion32` APK for `armeabi-v7a` and `x86`, while the Host native module remains limited to `arm64-v8a` and `x86_64`. A Bundle-free, signature-permission Binder contract carries protocol, session, generation, virtual user, APK revision, one-time nonce and requested ABI. Host routing rejects unknown ABI metadata and never silently executes a 32-bit Guest in the 64-bit Broker process. The companion module and JNI boundary are source/Host-compile verified; Android APK packaging, cross-package Binder behavior and full 32-bit Guest execution remain device-gated.

## M4-T17 source baseline

M4-T17 hardens Guest Native execution across filesystem, procfs, dynamic loading, IPv4/IPv6 network identity and audio capture authorization. Native ABI is now an explicit package/runtime field. The Host native runtime is limited to `arm64-v8a` and `x86_64`; a separate signature-permission `sandbox-companion32` APK carries `armeabi-v7a` and `x86` with a typed, generation-bound Binder contract. This baseline contains source and Host-native evidence only. Four-ABI Android packaging, cross-package Binder execution and complete 32-bit Guest lifecycle remain device-gated and fail closed in the current Host route.
