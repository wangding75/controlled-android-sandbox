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
