# M5-T18 development report

## Result

- Source status: PASS
- Production status: PARTIAL
- Android build: BLOCKED by the unavailable locked JDK 17 in the current environment
- Device evidence: 0

## Delivered

1. Added `RebindableServiceConnector`, a typed reusable Binder connector with Binder-death invalidation, disconnection handling, bounded exponential retry, close semantics and snapshots.
2. Migrated `RuntimeClient`, `PackageServiceClient`, the three `NativeCompanionClient` channels, `RuntimePermissionPackageClient` and `RuntimeVirtualSystemServicePackageClient` away from private one-shot connection state.
3. Added Host regressions for death/rebind, rejected first bind and adapted-capability cleanup.
4. Added `VirtualSecretCipher` using AES/GCM/NoPadding with a per-install app-private key, random nonces, AAD and atomic key creation.
5. Upgraded the virtual system-service persistence schema from 5 to 6. Passwords and auth tokens now persist as encrypted values; schema-1 through schema-5 plaintext data is read only for immediate migration.
6. Added fail-closed behavior for encrypted state copied without its original key and cleared partially decoded in-memory state before quarantine.
7. Extracted virtual account query and mutation responsibility to `VirtualAccountAuthority`.
8. Fixed Launcher callback unregister/register and GraphicsStats add/save-buffer method collisions with exact-first classification and regression tests.
9. Added `.github/workflows/source-gates.yml` with Temurin JDK 17, full local-source gates and verification artifact upload. Added a custom `SourceBaselineInstrumentation` entry for later `connectedDebugAndroidTest` execution; it has not run in this environment.
10. Updated the source version to `versionCode 18` and `versionName 0.5.18-source` without claiming an APK was built.
11. Preserved all twelve legacy Bundle AIDL methods as compatibility entry points; repository-owned primary execution remains on the typed V2 transport.
12. Preserved the frozen 113-category matrix and modified no file under `ref/upstream`.

## Remaining architecture debt

The iteration performs an initial responsibility extraction, not a complete large-class refactor. fourteen production Java classes remain above 500 lines, including `ActivityTaskLedger` (1,741), `VirtualSystemServiceStore` (1,654), `RuntimeBrokerService` (1,368) and `PackageManagementService` (1,191). Further splitting must be driven by build/device findings and focused ownership boundaries rather than mechanical line reduction.

## Honest security boundary

AES-GCM removes plaintext account secrets from the durable JSON store. The encryption key is still an app-private per-install file key because Android Keystore cannot be validated in the current Host-only environment. Root, full application-data compromise or key-and-ciphertext copying remain outside this source guarantee. Android Keystore migration is device-stage work.

The CI workflow is defined but has not run on GitHub in this environment. Real Binder death/rebind timing, generated AIDL, Android process lifecycle and device behavior remain unverified.
