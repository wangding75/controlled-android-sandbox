# M5-T18 architecture quality and pre-release source governance

## Goal

Harden the M5-T17 source baseline before Android build and device execution. The iteration improves Binder reconnection, account-secret persistence, method classification, large-state ownership and continuous source gates without expanding the frozen capability matrix or claiming device compatibility.

## Authorized source scope

1. Replace one-shot Binder clients with a reusable connector that handles Binder death, `onBindingDied`, disconnects, null bindings and bounded exponential retry.
2. Encrypt virtual-account passwords and auth tokens at rest with AES-GCM, migrate schema-5 plaintext data to schema 6, and fail closed when encrypted state cannot be decrypted.
3. Extract account operations from `VirtualSystemServiceStore` into a dedicated authority while preserving atomic persistence and rollback.
4. Repair known inverse/sub-string method classification collisions and add direct lifecycle regressions.
5. Add a JDK-17 source-gate CI workflow, a device-stage Android instrumentation smoke entry and preserve reproducible local gates.
6. Record remaining large classes and twelve legacy Bundle AIDL compatibility methods instead of reporting them as eliminated.
7. Preserve `ref/upstream`, the frozen 113-category matrix and zero Android/device evidence.

## Acceptance

- five production Binder clients use the reusable connector and no longer own one-shot `ServiceConnection` state;
- Binder death, rejected binding, retry and adapted-capability close behavior have Host regression evidence;
- account secrets are stored only in `passwordEncrypted` and `tokensEncrypted` schema-6 fields;
- legacy plaintext account data migrates immediately, and missing/wrong keys quarantine state and fail closed;
- Launcher callback unregister and GraphicsStats add-to-save-buffer collisions have direct regression tests;
- package-boundary, architecture, contract and static Android gates pass;
- CI is defined with locked JDK 17, while its execution status is not fabricated;
- Android Keystore integration, real Binder timing and Android/device evidence remains zero until the locked build/device environment is available.
