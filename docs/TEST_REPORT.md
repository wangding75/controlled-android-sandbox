# M4-T3 local test report

Date: **2026-07-27**

Status: **LOCAL PASS / SOURCE 96.2% / PRODUCTION WIRING 91.0% / DEVICE EVIDENCE 0.0%**

## Evidence dimensions

The capability matrix contains 40 tracked capabilities and reports three independent dimensions:

- Source: 37 complete, 3 partial, 0 missing; weighted 96.2%.
- Production wiring: 33 wired, 5 partial, 1 blocked, 1 not applicable; weighted 91.0%.
- Device verification: 0 verified, 0 partial, 38 not tested, 1 blocked, 1 not applicable; weighted 0.0%.

These percentages describe repository evidence and must not be interpreted as third-party APK compatibility rates.

## M4-T3 additions verified locally

- Package records and virtual instances are validated and persisted as one atomic catalog.
- Legacy independent metadata is normalized only when no catalog exists.
- Legacy mutable APK/native payloads are copied and verified into canonical SHA-256 revision directories.
- Existing revision reuse requires matching APK and native-file digest maps.
- Catalog paths reject outside-root locations, missing payloads and managed symbolic-link traversal.
- Import/upgrade publishes an immutable revision before switching the catalog and rolls back an unreferenced revision when the catalog switch fails.
- Delete switches the catalog before instance/package cleanup.
- Stale revisions, interrupted install/migration directories, legacy payloads and orphan instance directories are swept.
- Post-commit cleanup failures are retained as maintenance warnings rather than being reported as metadata transaction failures.
- `RecoverableFileStore` writes backup before primary and restores/removes the backup when synchronous primary publication fails.
- Product and debug command paths both use `SandboxPackageLifecycle` instead of writing legacy repositories directly.

## Other gates passed locally

- Domain, architecture, contract, package-boundary and deterministic SBOM checks.
- Static compilation of Java production/test sources with local Android/AIDL stubs.
- Guest Context and class-loader boundary tests.
- Immutable APK revision and stale-Session replacement tests.
- Activity, Service, Receiver and Provider source/runtime model tests.
- Framework identity/proxy and rollback tests.
- Native policy, filesystem/network hook and crash-recorder host tests.
- Reproducible source ZIP two-run byte comparison.
- Wrapper checksum and fail-closed bootstrap tests.

## Not executed

Per the current development instruction, Emulator, physical-device, ADB, real third-party APK and device behavior tests are skipped. The current host has JDK 21 while the locked Android build requires JDK 17, and no Android SDK/NDK build is claimed in this iteration.

The following remain unverified:

- Android filesystem rename and sudden-power-loss behavior.
- Binder/process coordination under real Android scheduling.
- Package migration from an actually installed older application build.
- Runtime behavior of split APKs, native libraries and dex caches.
- Compatibility with real third-party applications.
