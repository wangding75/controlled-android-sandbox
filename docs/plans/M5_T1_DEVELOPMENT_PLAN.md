# M5-T1 Development Plan — Real Android Build Baseline

## Baseline

- Source baseline: `m4-t18-source-pass` / `f133110882cce7e5c97b3a587334382ac063bf82`
- Scope: build-system and artifact-verification development only
- Device execution, Emulator compatibility, and physical-device validation are outside this task

## Goal

Produce one locked and fail-closed build path for the three APKs required by the first Emulator phase:

1. 64-bit Host APK
2. 64-bit Fixture APK
3. 32-bit Native Companion APK

The build must reject an incomplete SDK, wrong JDK, wrong ABI packaging, missing native libraries, or untracked artifact composition.

## Locked toolchain

| Tool | Version |
|---|---|
| JDK | 17 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.11.1 |
| Compile SDK | 36 |
| Target SDK | 35 |
| Build Tools | 35.0.0 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |

## ABI split

| Artifact | ABI set |
|---|---|
| Host | `arm64-v8a`, `x86_64` |
| Fixture | `arm64-v8a`, `x86_64` |
| Companion32 | `armeabi-v7a`, `x86` |

A 32-bit ABI in the Host APK, a 64-bit ABI in Companion32, or a missing required native library is a hard failure.

## Deliverables

- Machine-readable SDK package and APK artifact declarations in `build-environment.lock.json`
- Cross-platform locked SDK component installation scripts
- Cross-platform three-APK build scripts
- APK ABI/native-library verifier and deterministic artifact manifest
- Reproducible release build updated to include Companion32
- Static and synthetic fail-closed tests integrated into `verify-all.sh`
- Development report, verification log, complete source ZIP, Git bundle, and patches

## Acceptance

### Source PASS

- All existing M4 gates pass
- M5 build baseline static gate passes
- Synthetic APK verifier accepts the correct three-APK set
- Synthetic APK verifier rejects ABI leakage
- Shell, Python, and PowerShell structural checks pass
- Source package remains reproducible

### Android build PASS

Requires external Android toolchain availability. The following command must complete and produce validated APKs:

```bash
./scripts/build-device-test-apks.sh --online
```

The current execution container has no Android SDK and no external dependency download path. This environmental limitation must be reported separately and must not be represented as an APK build PASS.
