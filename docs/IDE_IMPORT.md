# Android Studio import

1. Extract the source ZIP to a short path, for example `D:\github\controlled-sandbox-cleanroom`.
2. Install JDK 17, Android SDK Platform 36, Build Tools 35.0.0+, Android NDK and CMake 3.22.1.
3. Open the repository root containing `settings.gradle`.
4. Set **Gradle JDK** to JDK 17.
5. Allow Gradle 8.13 and Android Gradle Plugin dependencies to resolve, or use a prepared cache.
6. Sync the project.
7. Build both `fixture-basic` and `app` debug variants.

Command line:

```powershell
.\gradlew.bat clean check :fixture-basic:assembleDebug :app:assembleDebug
```

Outputs:

```text
fixture-basic\build\outputs\apk\debug\fixture-basic-debug.apk
app\build\outputs\apk\debug\app-debug.apk
```

The debug host includes an exported test-only command Activity used by `run-emulator-m3.ps1`. It is absent from release builds.

The source can be inspected and locally verified without an Android SDK using `scripts\verify-all.sh` from WSL, but that static-stub compilation is not an Android build.

## Locked build environment

Before the first Android build, install the exact versions in `build-environment.lock.json`. JDK 17 is required for reproducible Android builds. Newer JDKs may run host-only verification but are rejected by the strict Android environment gate.

Populate the dedicated dependency cache once while online:

```powershell
.\scripts\bootstrap-build-cache.ps1
```

Then perform the release build without dependency access:

```powershell
.\scripts\reproducible-build.ps1 -VerifyTwice
```

The dedicated cache defaults to `.gradle-reproducible` under the repository and is excluded from Git.
