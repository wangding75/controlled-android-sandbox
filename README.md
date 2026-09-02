# Controlled Android Sandbox

Controlled Android Sandbox is a clean-room Android application virtualization
runtime. It installs a guest APK into a virtual package/session model and
launches guest components through host-owned Android processes and contracts.
The repository is source-first: build output, device logs, screenshots and
campaign session dumps are generated locally and are not part of the source
tree.

## 1. Project Overview

The runtime provides scope-limited virtualization for:

- guest package installation, package visibility, manifest parsing and split
  APK lifecycle;
- Activity, Service, BroadcastReceiver and ContentProvider component routing;
- Binder contracts, system-service capability brokering and package-manager
  projections;
- guest lifecycle, virtual identity, multi-process slots, process-generation
  recovery and clear/delete/reinstall transactions;
- file, network and native/JNI boundaries, including the 32-bit companion
  route;
- class-loader, ART, ABI and native-loader decisions needed for guest startup.

The runtime is not a claim of complete Android or VA Pro compatibility. The
current status, evidence policy and remaining gaps are machine-readable in the
[capability registry](docs/capability/CAPABILITY_REGISTRY.yaml). The VA/NBB
reference boundary is documented in
[VA_NBB_REFERENCE_BASELINE.md](docs/VA_NBB_REFERENCE_BASELINE.md).

## 2. Architecture

The product is layered around typed contracts and explicit ownership:

```text
app (host composition root)
 ├─ sandbox-sdk
 ├─ sandbox-runtime ── sandbox-framework ── sandbox-native
 ├─ sandbox-domain
 └─ sandbox-contract

sandbox-companion32 ── sandbox-runtime ── sandbox-framework ── sandbox-native
fixtures ── guest APK test inputs; fixture-split-feature ── fixture-split-base
```

`sandbox-domain` contains platform-neutral state and policy, while
`sandbox-contract` contains the typed Java/AIDL boundary. `sandbox-sdk` is the
business-facing API surface. `sandbox-framework` adapts Android component and
Binder behavior, `sandbox-runtime` owns guest sessions and brokers, and
`sandbox-native` contains native/JNI support. The host application is the
composition root that wires these layers; guest/business fixtures do not depend
directly on runtime, framework or native internals.

## 3. Repository Structure

- `app/` — host Android application and debug command surface.
- `sandbox-domain/` — platform-neutral domain model and policies.
- `sandbox-contract/` — typed Java and AIDL contracts.
- `sandbox-sdk/` — business-facing SDK and guest integration surface.
- `sandbox-runtime/` — guest sessions, process routing and brokers.
- `sandbox-framework/` — Android framework adapters and component bridges.
- `sandbox-native/` — shared native runtime and JNI implementation.
- `sandbox-companion32/` — signed 32-bit companion application.
- `fixture-basic/`, `fixture-compat32/`, `fixture-activity-scale/`,
  `fixture-lifecycle/`, `fixture-split-base/`, `fixture-split-feature/` —
  focused guest APK fixtures.
- `docs/` — current architecture, compatibility, policy, matrices, plans and
  machine-readable capability contracts.
- `reports/t57-r03/` — current T57-R03 convergence reports.
- `scripts/` — build, source-check, packaging and local self-test helpers.
- `tools/` — static analysis, lock/provenance checks and capability tooling.
- `verification/` — immutable baseline/provenance manifests, SBOM, CI locks and
  the active native-enforcement source fixture.
- `verification/native-enforcement/` — native source compiled into the debug
  enforcement fixture; it is active source, not historical evidence.
- `ref/` — byte-preserved, reference-only VirtualApp and NewBlackbox snapshots.
- `.github/` — pinned source-gate workflow and dependency update policy.
- `gradle/` — wrapper, dependency verification metadata and lock policy.

Generated `build/`, `.gradle*/`, `_delivery/`, `artifacts/` and
`verification/catch-up/` content is ignored and should remain outside commits.

## 4. Build Requirements

- JDK 17 (the repository records the major version in `.java-version`).
- Android SDK Platform 36.
- Android Build Tools 35.0.0.
- Android NDK 27.2.12479018.
- CMake 3.22.1.
- Gradle 8.13 through the checked-in wrapper and its checksum.
- Host APK ABIs: `arm64-v8a`, `x86_64`.
- 32-bit companion ABIs: `armeabi-v7a`, `x86`.

The exact toolchain contract is in
[build-environment.lock.json](build-environment.lock.json).

## 5. Build

Run from the repository root in PowerShell:

```powershell
.\gradlew.bat projects
.\gradlew.bat assembleDebug
.\gradlew.bat :app:assembleDebug :sandbox-companion32:assembleDebug
.\gradlew.bat :fixture-basic:assembleDebug :fixture-compat32:assembleDebug :fixture-activity-scale:assembleDebug :fixture-lifecycle:assembleDebug :fixture-split-base:assembleDebug :fixtureSplitFeature:assembleDebug
```

The wrapper uses strict dependency verification and the checked-in Gradle
metadata. Use `scripts/build-debug.ps1` or `scripts/build-debug.sh` when the
debug APK build helper is more convenient.

## 6. Testing

The standard source and unit-test commands are:

```powershell
.\gradlew.bat test
.\gradlew.bat check
.\gradlew.bat :sandbox-domain:test
.\gradlew.bat :sandbox-contract:test
python scripts/check-architecture.py
python scripts/check-contracts.py
python tools/static_android_compile.py
python scripts/check-build-environment.py
.\scripts\self-test.ps1
```

Fixture APK assembly is part of the build commands above. Native source checks
are available through `scripts/test-native.sh`; release-signing and source-ZIP
helpers have independent self-tests. The device/capability acceptance
pipeline is being reconsolidated and is intentionally not exposed as a single
`verify-all` entrypoint in this revision.

## 7. Reference Implementations

`ref/upstream/VirtualApp` and `ref/upstream/NewBlackbox` are immutable,
reference-only snapshots. They are used to compare observable architecture,
component lifecycles, package behavior, class loading, native boundaries and
compatibility expectations. They are not Gradle modules, are not compiled or
linked into the product, and are excluded from product source releases.

The clean-room restrictions and provenance checks are defined in
[CLEAN_ROOM_POLICY.md](docs/CLEAN_ROOM_POLICY.md),
[SOURCE_PROVENANCE.md](docs/SOURCE_PROVENANCE.md) and
[VA_NBB_REFERENCE_BASELINE.md](docs/VA_NBB_REFERENCE_BASELINE.md).

## 8. Current Development Baseline

The active development baseline is branch
`feature/t57-r03-va-pro-capability-campaign`. This revision retains
scope-limited source implementations and current T57-R03 reports while
keeping API 33–36, OEM, commercial-app and VA Pro equivalence claims
`UNVERIFIED` or `NOT_PROVEN` until their required matrices exist.

Use [CAPABILITY_REGISTRY.yaml](docs/capability/CAPABILITY_REGISTRY.yaml) for
the capability-by-capability status and
[KNOWN_ISSUES.yaml](docs/review/KNOWN_ISSUES.yaml) for the current issue
registry. Historical campaign dumps and generated device evidence are not the
source of truth for this baseline.

## 9. Documentation

Start with:

- [Architecture](docs/ARCHITECTURE.md), [clean-room policy](docs/CLEAN_ROOM_POLICY.md),
  [source provenance](docs/SOURCE_PROVENANCE.md), [threat model](docs/THREAT_MODEL.md)
  and [VA/NBB baseline](docs/VA_NBB_REFERENCE_BASELINE.md).
- [Capability registry](docs/capability/CAPABILITY_REGISTRY.yaml),
  [compatibility corpus](docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml),
  [evidence schema](docs/capability/CAPABILITY_EVIDENCE_SCHEMA.md) and
  [campaign workflow](docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md).
- Current [package matrices](docs/package/T57_R03_MANIFEST_SURFACE_MATRIX.yaml),
  [PMS matrix](docs/package/T57_R03_VIRTUAL_PMS_SURFACE_MATRIX.yaml),
  [system-service matrices](docs/system/T57_R03_BINDER_INTERCEPTION_MATRIX.yaml),
  [native boundary documents](docs/native/T57_R03_NATIVE_BOUNDARY_MATRIX.yaml),
  [class-loader matrix](docs/runtime/T57_R03_CLASSLOADER_MATRIX.yaml) and
  [OEM matrix](docs/compat/T57_R03_ANDROID_OEM_MATRIX.yaml).
- The retained execution plans under `docs/plans/`, the current issue registry
  under `docs/review/`, and the four convergence reports under
  `reports/t57-r03/`.

## 10. License / Third Party

Controlled Android Sandbox is distributed under the terms of
[LICENSE](LICENSE). Notices and third-party attributions are in
[NOTICE](NOTICE) and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
The `ref/` snapshots retain their own upstream license/provenance boundaries;
see [ref/README.md](ref/README.md) before using or redistributing them.
