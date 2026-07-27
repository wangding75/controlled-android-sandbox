# M4-T0 — frozen baseline and reproducible build foundation

## Scope

This iteration freezes the uploaded B3-T5A source as an immutable Git baseline and adds the first reproducible-build gate. Emulator and physical-device execution remain deliberately out of scope.

## Implemented

- Imported the uploaded source into `baseline/b3-t5a-upload` and tagged it as `baseline-b3-t5a-upload`.
- Created `feature/m4-reproducible-build` for all changes after the baseline.
- Added `build-environment.lock.json` as the machine-readable toolchain authority.
- Pinned Gradle 8.13 with the official binary distribution SHA-256.
- Reworked the clean-room Gradle bootstrapper to verify the archive, serialize concurrent installs, use atomic replacement and reject checksum mismatches.
- Made the checked-in Java source-file launcher authoritative; the compatibility JAR is checksum-pinned and behavior-tested.
- Centralized compile SDK, target SDK, minimum SDK, Build Tools, NDK and CMake versions.
- Added exact Android environment validation and offline deterministic build entry points.
- Added byte-identical deterministic source ZIP packaging and a two-package comparison gate.
- Replaced nondeterministic release ZIP traversal with sorted, normalized source packaging.
- Normalized repository line endings and editor defaults.

## Build modes

### Host verification

```bash
./scripts/verify-all.sh
```

This does not require an Android SDK. It verifies source structure, contracts, host Java/C++ tests, the wrapper checksum path and deterministic source packaging.

### Populate the locked dependency cache

```powershell
.\scripts\bootstrap-build-cache.ps1
```

This requires JDK 17 and all Android components named in `build-environment.lock.json`.

### Offline reproducible Android build

```powershell
.\scripts\reproducible-build.ps1 -VerifyTwice
```

The command performs two clean, cache-disabled, non-parallel release builds and rejects any APK whose bytes differ.

## Evidence boundary

The current execution environment does not contain the locked Android SDK/NDK toolchain, so the Android build script is implemented and statically checked but not executed here. This iteration raises build reproducibility and supply-chain integrity; it does not increase device compatibility evidence.
