# M4-T0 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-27

## Iteration scope

M4-T0 freezes the uploaded source baseline and strengthens build reproducibility. It does not add a new Activity, Service, Receiver, Provider, PackageManager or native-hook capability.

## New capability in this iteration

| Area | Controlled Sandbox M4-T0 result | Evidence |
|---|---|---|
| Source baseline | Immutable baseline branch and annotated tag | Git branch/tag plus `docs/BASELINE.md` |
| Toolchain | Machine-readable Java, Gradle, AGP, SDK, Build Tools, NDK and CMake lock | `build-environment.lock.json` |
| Gradle distribution | Version and official SHA-256 are mandatory | Wrapper properties and checksum-failure self-test |
| Wrapper bootstrap | Checked-in Java source-file launcher is authoritative; compatibility JAR is hash-pinned | Source compile, hash check and both launch paths tested |
| Offline build | Dedicated cache bootstrap and offline build entry point | `bootstrap-build-cache.*`, `reproducible-build.*` |
| Artifact reproducibility | Optional two-clean-build APK byte comparison | `-VerifyTwice` / `--verify-twice` |
| Source packaging | Sorted paths, normalized timestamps and permissions | deterministic ZIP tool and two-package gate |

## Build-system comparison

| Dimension | Controlled Sandbox M4-T0 | VirtualApp inspected branch | NewBlackbox inspected branch |
|---|---|---|---|
| Wrapper version pinned | Gradle 8.13 | Gradle 7.3.3 | Gradle 8.14.5 |
| Distribution SHA-256 in wrapper properties | Yes | Not present in inspected file | Not present in inspected file |
| Central SDK values | Yes | Build configuration is split through VA/App config files | Yes for compile/target/min SDK |
| Build Tools/NDK/CMake exact lock | Yes | Not established from inspected top-level files | Not established from inspected top-level files |
| Offline cache workflow | Present | Not observed in inspected build files | Not observed in inspected build files |
| Two-build byte comparison | Present | Not observed | Not observed |
| Deterministic source package gate | Present | Not observed | Not observed |

External source snapshots used for this narrow comparison:

- VirtualApp `master/gradle/wrapper/gradle-wrapper.properties`, inspected 2026-07-27.
- VirtualApp `master/build.gradle`, inspected 2026-07-27.
- NewBlackbox `master/gradle/wrapper/gradle-wrapper.properties`, inspected 2026-07-27.
- NewBlackbox `master/build.gradle`, `settings.gradle` and `gradle.properties`, inspected 2026-07-27.

“Not observed” only means the inspected files did not expose the control. It does not prove that no equivalent private CI or unpublished process exists.

## Sandbox capability position

This iteration does not close the principal virtualization gap.

| Capability class | Controlled Sandbox status after M4-T0 | Relative position |
|---|---|---|
| Component routing source coverage | Existing B3-T5A implementation retained | Still behind mature VA/NBB coverage and adaptation history |
| PackageManager/system-service virtualization | Partial | Material gap remains |
| Java/native hook breadth | Partial | Material gap remains |
| 32-bit ABI | Not supported in frozen baseline | Behind VA/NBB variants that include 32-bit support |
| Android API/device compatibility | Not tested by current project decision | Cannot claim parity |
| Build provenance and reproducibility | Substantially strengthened | Stronger than the controls visible in the inspected public build files |

## Test result

- Host verification: PASS.
- Wrapper checksum success path: PASS.
- Wrapper checksum mismatch fail-closed path: PASS.
- Wrapper source compilation and compatibility-JAR checksum: PASS.
- Deterministic source ZIP two-run byte comparison: PASS.
- Android SDK/NDK build: not executed in the current environment.
- Emulator and physical-device tests: deferred by project decision.

## Remaining work and next priority

1. Prevent Guest access to the host base Context and privileged management surfaces.
2. Bind runtime sessions to immutable APK revisions.
3. Make package install, upgrade and delete operations transaction-safe.
4. Expand Context storage and database redirection.
5. Continue PackageManager and system-service virtualization toward VA/NBB breadth.

## Completion interpretation

M4-T0 raises engineering reliability and baseline traceability. It does not increase the evidence level for third-party application compatibility. Existing source-complete and production-wiring percentages remain static-analysis metrics; device verification remains 0%.
