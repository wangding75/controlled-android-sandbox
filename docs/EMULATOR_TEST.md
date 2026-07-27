# Android Emulator M3 test

## Recommended host

- Windows 11 with hardware virtualization enabled.
- Android SDK command-line tools under `%LOCALAPPDATA%\Android\Sdk` or `ANDROID_SDK_ROOT`.
- JDK 17.
- Android NDK and CMake 3.22.1.
- Dependency download access or a prepared Gradle cache.

## Formal run

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\run-emulator-m3.ps1
```

Defaults:

- AVD: `ControlledSandbox_API35`
- image: `system-images;android-35;google_apis;x86_64`
- stability duration: 20 minutes
- host package: `com.warden.controlledsandbox.debug`
- Fixture package: `com.warden.controlledsandbox.fixture`

The script creates/boots an AVD when no online device exists, builds the host and Fixture, installs both, imports the installed Fixture into app-private storage, prepares/launches virtual users 0 and 1, requires successful Service/Receiver/Provider operations, validates JNI, loops foreground/background launches, and collects both Logcat and runtime-diagnostics JSONL evidence.

## Development-only shorter run

```powershell
.\scripts\run-emulator-m3.ps1 -StabilityMinutes 2 -KeepEmulator
```

A shorter run can diagnose failures but cannot satisfy `check-m3-release-gate.sh`, which always requires at least 1,200 seconds.

## Existing device

```powershell
.\scripts\run-emulator-m3.ps1 -Serial emulator-5554 -SkipSdkInstall
```

## Result

Evidence is written to `artifacts\m3-emulator-YYYYMMDD-HHMMSS`. The script returns non-zero if either virtual-user Activity creation is missing, the component suite fails, diagnostics are absent, required processes or instance roots are missing, a crash/ANR is detected, or the formal stability duration is not met.
