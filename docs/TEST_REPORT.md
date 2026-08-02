# M4-T5 local test report

Date: **2026-07-27**

Status: **LOCAL PASS / SOURCE 96.6% / PRODUCTION WIRING 91.9% / DEVICE EVIDENCE 0.0%**

## Evidence dimensions

The capability matrix contains 44 tracked capabilities and reports three independent dimensions:

- Source: 41 complete, 3 partial, 0 missing; weighted 96.6%.
- Production wiring: 37 wired, 5 partial, 1 blocked, 1 not applicable; weighted 91.9%.
- Device verification: 0 verified, 0 partial, 42 not tested, 1 blocked, 1 not applicable; weighted 0.0%.

These percentages describe repository evidence and must not be interpreted as third-party APK compatibility rates.

## M4-T5 additions verified locally

- Package Service builds a typed virtual package-state snapshot from the trusted immutable APK revision.
- Runtime requests bind that snapshot to package name, virtual user and APK SHA-256.
- Guest bootstrap rejects missing, foreign-identity or stale-revision snapshots.
- Catalog schema v2 atomically persists package/user permission and AppOps policies while remaining backward-readable from schema v1.
- Policy rows cannot outlive their virtual instance and are removed atomically on instance deletion.
- Permission and AppOps decisions are isolated by virtual user.
- PackageManager permission checks consume the virtual policy and hide direct host-package queries.
- PermissionManager and bounded AppOps calls return explicit virtual decisions without invoking host delegates.
- Package-management AIDL remains typed and contains no `Bundle` business payloads.

## Gates passed locally

- Domain, architecture, contract, package-boundary and deterministic SBOM checks.
- Binder-owned package authority and management authorization checks.
- Virtual package-state, permission and AppOps source gate.
- Static compilation of Java production/test sources with local Android/AIDL stubs.
- Guest Context, class-loader and immutable APK revision boundary tests.
- Package lifecycle transaction and Catalog recovery tests.
- Activity, Service, Receiver and Provider source/runtime model tests.
- Framework identity/proxy and rollback tests.
- Native policy, filesystem/network hook and crash-recorder host tests.
- Reproducible source ZIP two-run byte comparison.
- Wrapper checksum and fail-closed bootstrap tests.

## Not executed

Per the current development instruction, Emulator, physical-device, ADB, real third-party APK and device behavior tests are skipped. The locked Android build requires JDK 17 and a verified Gradle 8.13 distribution; those prerequisites must be satisfied without weakening the build lock before an AGP/NDK build is claimed.

The following remain unverified:

- Android PackageManager, PermissionManager and AppOps Binder signatures across API levels and OEM variants.
- Host manifest/runtime permission availability for a virtual `GRANTED` decision.
- Runtime permission dialogs, special access, roles and signature/privileged permission behavior.
- Complex AppOps return types, attribution chains and callbacks.
- Package Service restart and policy-snapshot refresh behavior under Android scheduling.
- Compatibility with real third-party applications.


# M4-T14 local test report

Status: **LOCAL PASS CANDIDATE / SOURCE 97.8% / PRODUCTION WIRING 95.5% / DEVICE EVIDENCE 0.0%**

The capability matrix contains 90 tracked entries. M4-T14 adds dedicated Service coordination, Binder-death cleanup, sticky/redeliver recovery and a partial foreground-service state model. Static Android compilation and Service-specific domain/Broker/coordinator regression tests pass. Emulator, physical-device, real APK and Android foreground-service policy tests remain intentionally deferred.


# M4-T15 local test report

Date: **2026-07-28**

Status: **LOCAL PASS CANDIDATE / SOURCE 97.9% / PRODUCTION WIRING 95.7% / DEVICE EVIDENCE 0.0%**

The capability matrix contains 95 tracked entries. M4-T15 adds selected Activity launch-flag semantics, forward-result ownership, no-history and recent-task policy, running/recent task queries, owner-checked task mutations and an atomic CRC-protected Activity/Task checkpoint. Static Android compilation, Activity ledger tests, checkpoint corruption tests and Runtime Broker restart restoration tests pass.

The checkpoint restores bounded Activity/task state but deliberately does not restore one-time route tokens, Binder-backed transport authority or pending result delivery. Android framework task-object projection, system Recents behavior, Window transitions, Emulator execution and real APK compatibility remain unverified.
## M5-T19 source-quality verification

M5-T19 adds direct tests for centralized method matching and a machine-readable critical-path test-ownership map. The architecture audit now scans every `*/src/main/**/*.java` source at runtime; the corrected M5-T19 baseline is thirteen large classes, including `ManifestReceiverRegistry.java`, while the current M5-T19.1-R source tree is reported independently from the live scan. All static Android and Host regressions pass. The ownership map explicitly does not claim JaCoCo line or branch coverage. Real AGP, Android instrumentation, Emulator and physical-device evidence remain unavailable until the locked JDK-17/SDK/NDK environment is used.
