# M5-T5 Development Report — Locked Four-APK Android Device Lab

## Status

- Source status: PASS
- Device-lab automation: PASS under synthetic artifact/evidence fixtures
- Android APK build: BLOCKED by the current toolchain environment
- Emulator evidence: 0
- Physical-device evidence: 0
- Base commit: `fb89a8fb215546f05285d1be1d9ee5a309b470bc`

M5-T5 does not claim that Android APKs were built or that an Emulator ran. It closes the source-side gap between the completed runtime implementation and a reproducible device experiment.

## Delivered

### Independent 32-bit Fixture

- Adds `fixture-compat32` with application ID `com.warden.controlledsandbox.fixture32`.
- Reuses the same Activity, Service, Receiver and Provider source as the 64-bit Fixture while restricting native packaging to `armeabi-v7a` and `x86`.
- Removes hard-coded package paths and package-scoped broadcast assumptions from shared Fixture code.
- Requires every 32-bit debug operation to return a successful typed Companion32 probe with process bitness 32.

### Four-APK build contract

The locked device-lab set is exactly:

1. Host: `arm64-v8a`, `x86_64`;
2. Fixture64: `arm64-v8a`, `x86_64`;
3. Fixture32: `armeabi-v7a`, `x86`;
4. Companion32: `armeabi-v7a`, `x86`.

The artifact verifier checks application ID, exact ABI set, required native libraries, ZIP safety, duplicate entries, hashes and source commit. The historical M5-T1 three-APK profile remains unchanged.

### Locked toolchain bootstrap

Windows and Linux scripts:

- require JDK 17;
- import a checksummed Android Command-line Tools archive from a local cache by default;
- optionally download the exact frozen archive;
- install the locked SDK, Build Tools, NDK, CMake, Emulator and API 35 x86_64 system image;
- validate the resulting environment before recording toolchain evidence.

### Device-lab runner

The runner:

- rejects ambiguous multiple-device selection;
- creates or selects only the frozen AVD;
- requires both `x86_64` and `x86` device ABI support;
- installs Companion32, Host, Fixture64 and Fixture32 in deterministic order;
- executes import/prepare, component-suite and launch commands for virtual users 0 and 1 on both Fixtures;
- requires Companion32 PID and six or more successful typed 32-bit probes;
- runs a minimum 1,200-second stability loop for formal evidence;
- collects package, process, Activity, Service, memory, Logcat and runtime diagnostic evidence;
- fails on crash, ANR, missing runtime diagnostics, incomplete command evidence or insufficient duration.

Short diagnostic runs remain explicitly ineligible for formal PASS.

## Verification

Source-verifiable checks include:

- four synthetic APK positive verification;
- injected ABI-leak negative verification;
- build-manifest hash and commit validation;
- ADB device parsing and multiple-device safety;
- crash/ANR pattern detection;
- formal evidence positive and negative cases;
- static Android compilation of the shared Fixture and debug command route;
- Host and Native/JNI regression gates;
- reproducible source packaging.

## Evidence boundary

The current environment contains JDK 21 and no Android SDK, NDK, ADB or Emulator. External dependency download is unavailable. Therefore no real APK, AVD boot, cross-package Binder execution, Android component result or 20-minute stability result is claimed.

M5-T5 is a **source PASS / Android build and device BLOCKED** baseline. The first external run must use the locked scripts and its evidence must pass `scripts/check-m5-device-evidence.py` before device status changes from 0.
