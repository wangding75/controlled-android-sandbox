# M5-T5 Development Plan — Locked Android Build and Device Lab

## Baseline

- Source baseline: `fb89a8fb215546f05285d1be1d9ee5a309b470bc`
- Branch: `feature/m5-t5-device-lab`
- The current execution environment has Java 21 only, no Android SDK/NDK/Emulator cache and no external DNS access.
- Device evidence remains outside the source PASS boundary until a locked JDK 17 and Android SDK environment executes the formal run.

## Frozen scope

### 32-bit device fixture

- Add an independent `fixture-compat32` APK containing Activity, Service, Receiver, Provider, remote-process and Native probes.
- Restrict the APK to `armeabi-v7a` and `x86` and keep the existing Fixture restricted to `arm64-v8a` and `x86_64`.
- Remove package-name assumptions from the Native probe so both Fixture APKs use the same source safely.

### Four-APK device-lab build contract

- Freeze Host, 64-bit Fixture, 32-bit Fixture and Companion32 tasks, application IDs, ABI sets and required native libraries.
- Extend the artifact verifier without weakening the historical M5-T1 three-APK contract.
- Produce hashes and a machine-readable device-lab build manifest.

### Locked toolchain and AVD bootstrap

- Pin Android Command-line Tools archives and SHA-256 values for Windows and Linux.
- Provide offline-first Windows and Linux bootstrap scripts.
- Install the existing locked SDK/NDK/CMake packages plus Emulator and the API 35 Google APIs x86_64 system image.
- Create an AVD only from the frozen profile and reject devices without both x86_64 and x86 support.

### Formal emulator execution

- Install Companion32, Host, 64-bit Fixture and 32-bit Fixture in deterministic order.
- Execute import, prepare, Activity, Service, Receiver and Provider flows for virtual users 0 and 1 on both Fixtures.
- Prove the Companion32 process is alive and running as a 32-bit Android process before accepting cross-width evidence.
- Run a minimum 1,200-second foreground/background stability loop.
- Collect package, process, Activity, Service, memory, Logcat and runtime-diagnostic evidence.
- Fail on crash, ANR, missing Guest lifecycle evidence, missing Companion32 evidence or incomplete stability duration.

### Evidence gate

- Validate the final device-lab JSON independently of the runner.
- Require the exact source commit, all four APK hashes, both Fixtures, both virtual users, 32-bit process evidence, zero fatal events and at least 1,200 seconds.
- Short diagnostic runs may be executed but cannot satisfy the formal gate.

## Validation

- Static M5-T5 source gate.
- Synthetic APK tests for the four-APK artifact verifier.
- Unit tests for ADB parsing, fatal-log detection, manifest validation and formal evidence validation.
- Existing M4-T14 through M5-T4 regression gates.
- Static Android compilation, Host tests, Native/JNI tests and reproducible source-package comparison.
- Real Android build and Emulator execution are attempted only when the locked external toolchain is available.

## Delivery

After source PASS:

- fast-forward merge to local `main`;
- complete source ZIP and Git bundle;
- M5-T4 to M5-T5 patch and cumulative patch;
- plan, development report, VA/NBB comparison, verification log and SHA-256 manifests;
- explicit separation of source PASS from Android build/device status.

## Execution result

**Execution status: SOURCE PASS / ANDROID BUILD AND DEVICE BLOCKED**
