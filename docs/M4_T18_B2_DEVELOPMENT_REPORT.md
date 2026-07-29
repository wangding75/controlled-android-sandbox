# M4-T18 B2 Development Report — Ownership, Rollback and Cleanup Closure

## Baseline

- Starting point: `7d45947` (`m4-t18-b1-source-pass`).
- Device/emulator execution: outside this batch.
- Acceptance boundary: capacity, ownership identity, persistence rollback, Binder/process death cleanup, APK Revision cleanup and Guest-query Host-fallback closure only.

## Delivered changes

### Machine-readable lifecycle audit

Added `verification/m4-t18-resource-lifecycle-audit.json` and `check-m4-t18-ownership-cleanup.py`.

The gate covers 12 resource domains:

- recoverable metadata;
- install sessions;
- runtime sessions;
- Broker transient state;
- Activity/Task state;
- Service runtime;
- Receiver tokens;
- Provider resources;
- virtual system services;
- local PendingIntent records;
- capability leases;
- Native Companion state.

It also audits three Guest-facing query surfaces: PackageManager, Activity/Task and virtual system services. The gate is part of `verify-all.sh` and fails when a required capacity, ownership, rollback or cleanup invariant disappears.

### Bounded transient and registry state

Added explicit limits to previously unbounded or partially bounded state:

- Broker prepared specs: 64;
- Broker route payloads: 1,024;
- prepared payload size: 1 MiB;
- route payload size: 512 KiB;
- Activity tasks: 256;
- Activity records: 2,048;
- Service records: 1,024;
- Service connections per service: 256;
- Provider authorities: 2,048;
- Provider observers: 256;
- capability leases: 256;
- local PendingIntent records: 1,024;
- recoverable metadata file: 8 MiB by default;
- runtime session history: bounded with terminal-session pruning.

Overflow fails closed with domain-specific errors. Rejected capability resources are explicitly cleaned up rather than abandoned.

### Persistence rollback closure

`RecoverableFileStore` now rejects oversized reads and writes while retaining its backup-first publication and primary-write rollback behavior.

Two Job execution compensation paths were corrected:

- `linkToDeath` failure now restores the exact pre-change Job state using `MutationSnapshot` and `persistOrRestore`;
- failure while persisting `RUNNING` state invalidates the transient execution capability and transactionally restores `SCHEDULED` state.

If the compensation write itself fails, the failure is recorded as a maintenance warning instead of silently leaving an apparently successful state transition.

### Guest-query Host-fallback closure

`PackageManagerInvocationHandler` now returns isolated safe defaults for foreign or unknown package, UID and Intent query paths. These query failures no longer delegate to the Host PackageManager.

`VirtualSystemServiceInterceptor` now fails closed for unsupported Notification and Job mutations and returns scoped safe defaults for unsupported query signatures. Activity/Task query paths remain Broker-only and have no Android host fallback.

A reviewed exception remains for non-virtualized framework calls after explicit inbound/outbound identity rewriting; this exception is recorded in the lifecycle audit and does not cover virtual package, task or system-service query surfaces.

### Ownership and cleanup closure

The audited registries retain explicit Session, Generation, virtual user, Guest package and APK Revision ownership where applicable. Existing death and removal cleanup is now enforced by the source gate for:

- Service connection Binder death;
- Provider observer death;
- session/process invalidation;
- APK Revision pruning;
- instance removal;
- PendingIntent and capability cancellation;
- Native Companion generation and nonce cleanup.

## Verification

PASS:

- M4-T18 source-closure gate;
- M4-T18 ownership/cleanup gate: 12 domains and 3 query surfaces;
- package lifecycle transaction checks;
- architecture, typed AIDL and package boundaries;
- M4-T14 Service regression;
- M4-T15 Activity/Task regression;
- M4-T16 PendingIntent, Alarm, Notification and Job regression;
- M4-T17 Native/ABI regression;
- static Android compilation and all Host self-tests;
- Native/JNI tests;
- strict M3 evidence gate;
- reproducible source ZIP byte comparison;
- Shell, Python and PowerShell structural checks.

The unified verification command reached the execution environment limit after the capability matrix, with no failure output. Static Android, Native, strict evidence and reproducible-package gates were then continued in their original order and all passed.

## Remaining B3 scope

- final full-repository review and safe duplicate cleanup;
- final capability evidence and VA/NBB comparison recalculation;
- unresolved device-capability and emulator prerequisite list;
- final repository statistics;
- clean `main` freeze and `m4-t18-source-pass` tag.
