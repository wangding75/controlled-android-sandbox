# C0-T02 build baseline — BLOCKED

## Identity and scope

- Task: `C0-T02` — 当前 HEAD 可复现构建基线
- Status: `BLOCKED`
- Baseline commit: `78d0a96e322a46912b061e53e750aa3a338de37e`
- Branch: `feature/t57-r03-va-pro-capability-campaign`
- Environment: Windows 11 amd64; PowerShell; JDK 17.0.18 (Zulu); Gradle 8.13;
  Android Gradle Plugin 8.11.1; compile SDK 36; target SDK 35; build tools 35.0.0;
  NDK 27.2.12479018; CMake 3.22.1
- Time window: 2026-08-21 12:05–12:06 (Asia/Shanghai)

## DISCOVER / CLASSIFY

The preflight checks passed:

```text
python scripts/check-build-environment.py --android
PASS Android build environment lock check (Java 17)

powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-wrapper-bootstrap.ps1
PASS wrapper source compile and compatibility JAR checksum

python tools/gradle_lock_state.py verify --require-clean
PASS Gradle-generated dependency lock state (48 files, 0 coordinates)

python scripts/check-m5-t19-1-u-supply-chain-governance.py
PASS M5-T19.1-U supply-chain governance (workflows=1 actions=3 runner=ubuntu-24.04 canonicalIdentities=3)
```

Classification: `TEST_EVIDENCE_GAP`, recorded as `KI-R03-BUILD-001`.
The failure is not an Android toolchain environment block: the locked JDK,
Android SDK/NDK/CMake declarations, wrapper checksum, dependency lock state,
and repository identity gate all passed. It is not classified as a runtime or
source compilation defect because Gradle stopped at strict dependency
verification before producing device APKs.

## DESIGN / IMPLEMENT_BATCH

The task's locked build entry point was used without source changes or
verification bypasses:

```text
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-device-test-apks.ps1
```

The script selected `clean`, `check`, `:app:assembleDebug`,
`:fixture-basic:assembleDebug`, and `:sandbox-companion32:assembleDebug`, with
`--no-daemon --no-build-cache --no-parallel --stacktrace --offline`.

## LOCAL_VERIFY result

The build failed at `:sandbox-contract:compileDebugLibraryResources`:

```text
Dependency verification failed for configuration ':sandbox-contract:detachedConfiguration2'
One artifact failed verification:
com.android.tools.build:aapt2:8.11.1-12782657
aapt2-8.11.1-12782657.pom from repository Google
Checksums are missing from verification metadata
```

The Gradle report is available at:

`build/reports/dependency-verification/at-1787285099295/dependency-verification-report.html`

The cached POM observed by the report was:

`D:\github\controlled-android-sandbox\.gradle-reproducible\caches\modules-2\files-2.1\com.android.tools.build\aapt2\8.11.1-12782657\5825e9f2d8264fa8ed9bb7ccddc5f7eeacf3dba\aapt2-8.11.1-12782657.pom`

Observed POM SHA-256 (untrusted until provenance review):
`43f1683656c6fcd2f24070bd781948427af61a67eb31a9a92fc0ffab9fc0953b`

`gradle/verification-metadata.xml` contains no verification entry for
`com.android.tools.build:aapt2:8.11.1-12782657`. No Host, fixture, or
Companion32 APK was produced, so no APK SHA-256 or artifact directory can be
claimed for this baseline. The CXX5202 32-bit warnings were observed but are
the expected warning recorded by the repository policy and are not the blocker.

No second build was attempted after the failure, per the task-book stop rule.

## Recovery condition

A trusted maintainer must review the aapt2 artifact provenance and either add
the verified checksum/signature to `gradle/verification-metadata.xml` or restore
a cache artifact matching already approved metadata. Then rerun the exact
locked command above and require all three APKs plus their SHA-256 evidence.
Do not use dependency-verification bypass flags or mark C0-T02 complete from
this failed run.

## Recovery attempt — still BLOCKED

- Recovery window: 2026-08-21 12:36–12:42 (Asia/Shanghai)
- Recovery baseline: `90ddc49a9a4e62cdbe238eb7c55436714d8044a8`
- Official source checked: Google Maven
  (`https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.11.1-12782657/`)
- Official POM SHA-256: `43f1683656c6fcd2f24070bd781948427af61a67eb31a9a92fc0ffab9fc0953b`
- Official Windows JAR SHA-256: `3ac122c7fc5a6b8bd5fd4b65b94fd00afc7ec69f51a00245fa05803517a588a7`
- The official POM/JAR bytes and detached signatures matched the cached files.
  The local environment has no `gpg` executable, so no standalone local GPG
  verification is claimed; Gradle's strict verification and the repository's
  existing `com.android.tools.build` trusted-key configuration remain the
  enforcement path.
- Changed governance files: `gradle/verification-metadata.xml`,
  `gradle/reviewed-dependency-coordinates.json`, and
  `gradle/dependency-verification-provenance.json`.
- Recovery preflight: Android environment lock PASS; wrapper checksum PASS;
  Gradle lock state PASS (48 files, 0 coordinates); M5-T19.1-U supply-chain
  governance PASS; strict offline Gradle `help` PASS.

The first post-recovery run of the exact command
`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-device-test-apks.ps1`
reached the Gradle `check`/lint stage but failed with `43 errors` and `30
warnings`. The primary report is:

`fixture-compat32/build/reports/lint-results-debug.txt`

The first errors are package-neutral fixture probe findings in
`fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/CapabilityProbeActivity.java`
(`getImei`, `getMeid`, `getSubscriberId`, `getSimSerialNumber`, cell info,
Wi-Fi scan, location, camera and `Build.getSerial`), followed by `NewApi`,
`WrongConstant`, and `SoonBlockedPrivateApi` findings in the framework probes.
No Host, fixture, or Companion32 APK was produced, and no second build was
attempted after this failure. This recovery attempt is therefore still
`BLOCKED` as `KI-R03-BUILD-002`; the next recovery must review the fixture
semantics and lint findings before another exact build run.

## Final recovery — DONE

- Recovery window: 2026-08-21 (Asia/Shanghai); the implementation baseline
  for the final build was `b35ca0feccf6c2d150766d8b9a740680d6f30057`.
- The original fixture lint findings were classified and fixed with explicit
  runtime API guards, SDK service/flag constants, method-level permission
  annotations for intentional probe calls, a targeted private-API annotation,
  and the optional camera hardware feature declaration. The full build then
  exposed and fixed equivalent compatibility findings in `sandbox-framework`,
  `sandbox-runtime`, and `app` with guarded API access and official constants.
- Targeted verification passed:

  ```text
  .\gradlew.bat --no-daemon --no-build-cache --no-parallel --stacktrace --offline :fixture-basic:lintDebug :fixture-compat32:lintDebug
  BUILD SUCCESSFUL
  .\gradlew.bat --no-daemon --no-build-cache --no-parallel --stacktrace --offline :sandbox-framework:lintDebug
  BUILD SUCCESSFUL
  .\gradlew.bat --no-daemon --no-build-cache --no-parallel --stacktrace --offline :sandbox-runtime:lintDebug
  BUILD SUCCESSFUL
  .\gradlew.bat --no-daemon --no-build-cache --no-parallel --stacktrace --offline :app:lintDebug
  BUILD SUCCESSFUL
  ```

- The exact locked command was run twice and both runs passed:

  ```text
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-device-test-apks.ps1
  PASS locked device-test APK set: host=3710513B, fixture=1766894B, companion32=42218435B
  PASS M5 device-test APK build: artifacts/m5-device-test-build/b35ca0feccf6
  ```

- The artifact manifest and SHA-256 evidence are in
  `artifacts/m5-device-test-build/b35ca0feccf6/build-manifest.json` and
  `artifacts/m5-device-test-build/b35ca0feccf6/SHA256SUMS.txt`.
  The first/second build comparison was:

  ```text
  host-debug.apk         e6c565f7f9349901f5ac91fc234a052e86c6409d1d7eeaa2e1695c33b8fdeb9d
  fixture-debug.apk      af85225a53002ce43084b5a32db5a17193be75c7bec0d2477780eb44702fb169
  companion32-debug.apk  cdb690449ee858954625a24f2683e15208dd7727bed9cb13a55bb82b61712483
  PASS deterministic APK SHA256 comparison
  ```

- Build-manifest checks passed for applicationId, signature, ABI, and native
  library inventories. No device runtime execution is claimed by C0-T02.
- `KI-R03-BUILD-001` and `KI-R03-BUILD-002` are now `FIXED` and do not block
  the campaign. The historical blocked sections above remain preserved for
  auditability; this evidence file's final status is `DONE`.
