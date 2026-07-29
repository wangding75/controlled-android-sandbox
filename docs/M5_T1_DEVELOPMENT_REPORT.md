# M5-T1 Development Report — Real Android Build Baseline

## Result

- Source implementation: **PASS**
- Actual Android APK build in this execution environment: **BLOCKED**
- Emulator or physical-device execution: **NOT STARTED**

The task does not claim that an APK was built. The Android build entry point was executed and stopped at the exact JDK gate because the container provides JDK 21 while the repository requires JDK 17. The container also has no Android SDK and cannot download external dependencies.

## Implemented

### Locked build contract

`build-environment.lock.json` now records:

- all four supported ABIs;
- exact SDK packages;
- Host, Fixture, and Companion Gradle tasks;
- expected APK paths and application IDs;
- allowed ABI set per APK;
- required native libraries per ABI.

### Toolchain setup

Added Linux/macOS shell and Windows PowerShell entry points that install the exact Platform, Build Tools, NDK, CMake, and Platform Tools versions through an existing Android `sdkmanager` installation.

### Three-APK build

Added cross-platform build entry points that:

1. enforce the exact environment lock;
2. verify the checked-in Gradle bootstrap;
3. run `clean`, `check`, and all three debug APK tasks;
4. collect artifacts under a commit-specific directory;
5. verify each APK before reporting success.

### Artifact verification

The verifier enforces:

- exact ABI composition;
- required `.so` presence in every expected ABI;
- no 32-bit ABI leakage into Host/Fixture;
- no 64-bit ABI leakage into Companion32;
- no duplicate or unsafe ZIP entries;
- exact application ID through locked `aapt2`;
- APK signature validity through locked `apksigner`;
- SHA-256 manifest and deterministic artifact naming.

### Existing build paths updated

- Gradle cache bootstrap now includes Companion32.
- Release reproducibility builds now include Companion32.
- Debug build wrappers now use the three-APK entry point.
- M5 static and synthetic fail-closed checks are part of `verify-all.sh`.

## Verification

Passed:

- host environment declaration check;
- M5 build baseline static gate;
- synthetic three-APK positive verification;
- fake `aapt2` application-ID verification;
- fake `apksigner` signature path;
- forbidden Companion 64-bit ABI rejection;
- all M4 source and architecture gates;
- static Android compilation against local stubs;
- all Host self-tests;
- all Native/JNI tests;
- strict M3 evidence gate;
- two-pass reproducible source ZIP comparison;
- shell, Python, and PowerShell structural checks.

## Actual build blocker

Command attempted:

```bash
./scripts/build-device-test-apks.sh --online
```

Observed result:

```text
Reproducible Android build requires Java 17; found 21
```

No APK, Emulator result, or device result is claimed.

## Next action

Run the locked toolchain installer and build on a workstation with JDK 17 and Android command-line tools. Once the three APKs pass artifact verification, continue with the first x86_64 Host + x86 Companion Emulator execution batch.
