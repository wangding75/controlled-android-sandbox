# M5-T5 Comparison with VirtualApp and NewBlackbox

## Scope

This comparison covers build and device-validation infrastructure only. It does not infer Android compatibility from source automation or public project claims.

| Function | Controlled Sandbox M5-T5 | VirtualApp reference position | NewBlackbox reference position | Remaining gap |
|---|---|---|---|---|
| 64/32-bit test applications | Separate Fixture64 and Fixture32 plus Companion32 are frozen in one four-APK contract | Mature branches have long-running 32-bit and 64-bit application experience | Public architecture has multi-ABI and 32/64-bit usage history | Controlled Sandbox APKs are not yet built |
| Cross-width proof | Every 32-bit operation must return typed Companion32 bitness and ABI evidence | Practical proof generally comes from device execution and issue history | Practical proof generally comes from device execution and issue history | No Android Binder/process evidence yet |
| ABI leakage gate | Exact APK ABI set and required `.so` files are machine validated | Public repositories do not consistently expose an equivalent release gate | Public repositories do not consistently expose an equivalent release gate | Does not prove Bionic/linker compatibility |
| Toolchain reproducibility | JDK, Gradle, AGP, SDK, NDK, CMake, command-line tools and system image are frozen | Historical branches often require period-specific manual environments | Public builds may depend on branch-specific Gradle/SDK state | Locked packages still need installation and execution |
| Emulator execution | Deterministic AVD, package order, two users, two Fixtures and 1,200-second gate | Broader historical API/device usage | Broader public user and issue evidence | No completed run in this baseline |
| Crash/ANR evidence | Formal result rejects fatal Logcat patterns and requires runtime diagnostics | Mature deployments have greater system and OEM incident history | Existing user reports provide broader real-device behavior | Android tombstone/AMS/OEM correlation still absent |
| Evidence audit | Independent validator binds commit, APK hashes, commands, bitness and duration | Evidence practices differ across releases and forks | Evidence practices differ across releases and forks | No comparable same-device benchmark exists |

## Judgment

M5-T5 improves Controlled Sandbox engineering discipline by making four-APK, cross-width and stability evidence reproducible and fail-closed. It does not narrow the practical compatibility lead held by VirtualApp and NewBlackbox because those projects still have substantially more Android-version, device, OEM and third-party application execution history.

Device evidence remains 0. Controlled Sandbox can only move from source readiness to product comparison after the locked APK set is built and the formal Emulator run passes.
