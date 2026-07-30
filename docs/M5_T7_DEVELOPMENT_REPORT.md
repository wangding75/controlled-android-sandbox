# M5-T7 development report

## Result

- Source status: PASS
- Production status: WIRED for the expanded package surface
- Android build: BLOCKED by the unavailable locked JDK 17 and Android SDK/NDK
- Device evidence: 0

## Delivered

1. Binary manifest parsing now retains required/optional Java and native libraries, versioned SDK/static library requirements, provider `<library>` names and instrumentation declarations.
2. Required shared libraries are resolved against an injected deterministic catalog during import and runtime-state construction. Missing, version-mismatched or certificate-mismatched required libraries fail closed; optional misses remain explicit.
3. Guest PackageManager exposes instrumentation queries and version-tolerant resolved `SharedLibraryInfo` projections without Host package fallback.
4. PackageInstaller-style sessions persist typed parameters, artifact count and bytes, progress, timestamps, attempt count, failure evidence and explicit retry across process restart.
5. Unsupported inherit-existing, rollback and nonzero install-flag commit behavior is rejected rather than silently approximated.
6. VA/NBB reference trees remain read-only, excluded from product builds and included in internal backups.

## Deferred to Android execution

- platform/OEM shared-library availability and ART namespace behavior;
- actual `SharedLibraryInfo` constructor variants;
- Instrumentation process launch and lifecycle;
- PackageInstaller callbacks, user-action flow, inherit-existing merge and rollback;
- all API/OEM compatibility results.
