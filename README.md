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
- Guest-generation PendingIntent sender identity and Broker routing, plus a Package-Service-owned scoped Binder authority for cross-process Clipboard, basic Account and persistent Alarm ownership. Notification/Channel owned resources and Job specs now persist with safe scoped `cancelAll`; a trusted host Job callback requests rescheduling until the version-safe Guest `JobParameters` bridge is complete. Receiver and Provider cleanup authorities are extracted from the central Runtime Broker.
- WebView data-directory suffix per virtual user/process slot.
- Native C++ path/network policy engine with ARM64/x86_64 Android build definitions and a host-side self-test.
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
- Arbitrary Guest native-library file/network interception; the current native module is a policy engine and JNI boundary, not a general libc hook.
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
