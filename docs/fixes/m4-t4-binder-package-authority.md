# M4-T4 — Binder-owned package authority and management capability

## Scope

This source-only iteration moves package and virtual-instance mutations out of Activity-owned objects and into one dedicated Android service process. Emulator, physical-device and third-party APK execution remain deferred.

## Defects closed

The M4-T3 lifecycle object was synchronized but lived inside each caller process. That left three architectural risks:

1. A future second caller process could construct another lifecycle object and race catalog writes.
2. Guest processes shared the application UID and could bind to non-exported same-UID services unless each management operation established a stronger caller boundary.
3. Package-management AIDL could have regressed to untyped `Bundle` payloads as operations expanded.

## Implemented

### Single Binder authority

- Added `PackageManagementService` in the dedicated `:sandbox_package` process.
- The service owns the only production `SandboxPackageLifecycle` instance.
- Import, file import, catalog load, lookup, clone creation, instance creation, runtime-status update, deletion and maintenance-status queries are serialized by one service-owned lock.
- `MainActivity` and `DebugCommandActivity` now use `PackageServiceClient`; neither constructs the lifecycle or writes package metadata directly.

### Capability-based management session

The root Binder exposes only `openManagementSession(IBinder clientToken)`.

A session is minted only when all conditions pass:

1. `Binder.getCallingUid()` equals the application UID.
2. `Binder.getCallingPid()` exists in Android's `ActivityManager` process registry.
3. The system-recorded process name exactly equals the host main process package name.
4. A live client Binder token is supplied and linked to death.

The returned `IPackageManagementSession` Binder is the management capability. Every operation rechecks:

- owner UID;
- owner PID;
- current main-process identity in the Android process registry;
- session open state.

Guest processes such as `:guest0`, the runtime broker process and the package-service process cannot mint or reuse the capability. Client death closes the session and PID reuse cannot revive it because the death-linked capability is closed.

### Typed Binder contract

Added typed Parcelable contracts:

- `PackageRecordSnapshot`;
- `PackageInstanceSnapshot`;
- `PackageCatalogSnapshot`;
- `PackageServiceResult`.

The package-management AIDL contains no `Bundle` payloads. Errors are returned as explicit operation, error-code and message fields.

### Evidence and gates

- `PackageManagementAuthorizationSelfTest` verifies main-process acceptance and rejection of Guest process, package-service process, foreign UID, unknown PID, wrong session owner and closed sessions.
- `PackageServiceContractSelfTest` executes Parcelable round trips for package, instance, catalog and result payloads.
- `check-package-service-boundary.py` enforces the dedicated process, non-exported service, typed AIDL, caller PID/UID checks, death token, operation serialization and absence of direct lifecycle construction from product callers.
- The capability is added to the multi-dimensional source/production/device evidence matrix.

## Evidence boundary

This iteration proves source wiring and host-side policy behavior. It does not prove Android Binder, `ActivityManager`, service restart or process-death behavior on a device.

The application still runs Guest code under the host application UID. The management capability reduces accidental and ordinary Guest access to privileged package operations, but it is not a complete hostile-code isolation boundary. Kernel compromise, host-process injection or a host-side capability leak remain outside this model.

## Build limitation in the current execution environment

The full host gate passes. The locked Android build requires JDK 17 and a verified cached Gradle 8.13 distribution. The execution container currently has JDK 21 and no Gradle distribution cache, so an actual AGP build cannot be reproduced here without weakening the lock or downloading dependencies. Neither workaround was used.

## Next priority

1. Expand the virtual PackageManager state model and query surface.
2. Add package permission and AppOps state owned by the package service.
3. Add split APK/install-session state and immutable multi-artifact revisions.
4. Reduce the shared-UID management surface further and audit all root Binder services.
