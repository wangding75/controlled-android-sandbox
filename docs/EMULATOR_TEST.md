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

## M5-T5 formal four-APK device lab

The M5-T5 path supersedes the older two-APK M3 smoke flow for cross-width evidence.

### 1. Bootstrap the locked toolchain

Windows:

```powershell
.\scripts\bootstrap-m5-device-lab.ps1 -AcceptLicenses
```

Linux:

```bash
./scripts/bootstrap-m5-device-lab.sh --accept-licenses
```

The default is offline-first. Place the frozen Android Command-line Tools archive in `.toolchain-cache`, or explicitly enable online download.

### 2. Build and validate four APKs

Windows:

```powershell
.\scripts\build-device-lab-apks.ps1
```

Linux:

```bash
./scripts/build-device-lab-apks.sh
```

### 3. Run the formal official-Emulator experiment

Windows:

```powershell
.\scripts\run-emulator-m5.ps1 -Headless
```

Linux:

```bash
./scripts/run-emulator-m5.sh --headless
```

Formal mode always requires at least 1,200 seconds. For diagnosis only, use `--diagnostic --stability-seconds 0` on Linux or `-Diagnostic -StabilitySeconds 0` on Windows; diagnostic output cannot satisfy the release gate.

### 4. Run the formal MuMu `RD测试` instance

```powershell
.\scripts\run-emulator-m5.ps1 -MumuInstanceName 'RD测试' -KeepEmulator
```

The runner resolves the selected MuMu instance from its current configuration, connects the
current ADB endpoint, requires `get-state=device`, and uses that resolved serial for this run.
`RD测试` and AVD evidence are separate environments. The resolution receipt is stored as
`mumu-instance-resolution.json` in the evidence directory.

### 5. Independently validate evidence

```bash
python3 scripts/check-m5-device-evidence.py artifacts/m5-device-lab-*/device-lab-result.json
```

A PASS requires the exact commit, hashes for all four APKs, both virtual users, all required 64/32-bit command flows, Companion32 process bitness 32, runtime diagnostic files, zero fatal findings and a minimum 1,200-second observation.
