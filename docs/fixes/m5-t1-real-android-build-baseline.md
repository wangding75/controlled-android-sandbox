# M5-T1 — Real Android Build Baseline

M5-T1 turns the prior declarative build lock into an executable three-APK contract. The Host and Fixture remain 64-bit (`arm64-v8a`, `x86_64`), while the independent Companion remains 32-bit (`armeabi-v7a`, `x86`).

The build entry point reads tasks and artifact paths from `build-environment.lock.json`, runs the exact environment check, builds all three modules, and then verifies APK ZIP composition. Verification checks exact ABI sets, required native libraries per ABI, duplicate or unsafe ZIP entries, checksums, and deterministic artifact metadata.

The source gate does not claim that Android APKs were produced in environments without JDK 17, SDK 36, Build Tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1, Gradle dependencies, and Android command-line tools.
