# M4-T3 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-27

## Iteration scope

M4-T3 replaces independent package/instance writes and mutable APK replacement with one atomic catalog and immutable SHA-256 revision publication. It does not add Android component coverage or claim device compatibility.

## New capability in this iteration

| Area | Controlled Sandbox M4-T3 result | Evidence |
|---|---|---|
| Package authority | Package and virtual-instance metadata share one validated catalog | `SandboxCatalogState`, `SandboxCatalogRepository` |
| APK revision storage | APK and extracted native files use immutable SHA-256 revision directories | `ApkImportManager` |
| Upgrade commit | New revision is published before one atomic catalog switch | `SandboxPackageLifecycle` |
| Failed catalog switch | Unreferenced newly published revision is rolled back; cleanup failure is attached | Lifecycle source and source gate |
| Delete commit | Catalog changes before instance/package file cleanup | Lifecycle source and source gate |
| Legacy baseline migration | Mutable package payloads are copied and verified into canonical revision paths before first catalog commit | `LegacyPackageLayoutMigrator` and executable self-test |
| Path integrity | Catalog and importer reject outside-root paths and managed symbolic-link traversal | `PackageStorageLayout` and source gate |
| Interrupted work | `.install-*`, `.migration-*`, stale revisions, legacy payloads and orphan instance directories are swept | Lifecycle source |
| Recovery ordering | Last-known-good copy is written before primary state; synchronous primary failure restores/removes the backup | `RecoverableFileStore` and domain self-test |
| Product integration | UI import/clone/status/delete use one lifecycle authority | `MainActivity` |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T3 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Basic package transaction model | Explicit immutable revision and single-catalog commit now exist | Mature virtualization engines expose broader package-manager lifecycle machinery | Local correctness gap reduced |
| Upgrade/runtime consistency | Immutable revision paths complement M4-T2 Session revision binding | Mature engines coordinate more package, process, dex, native and framework caches | Stronger local model; breadth gap remains |
| Package metadata breadth | One package record plus virtual instances | VA/NBB-style engines generally model substantially more Android package state | Material gap remains |
| Split APK/install sessions | Not implemented | Mature package layers commonly need split and multi-artifact handling | Behind |
| Permission/shared-library/dex state | Not part of this iteration | Broader virtual package managers mediate these surfaces | Behind |
| Cross-process package authority | Current lifecycle lock is in-process | Mature engines generally centralize package operations in a service/process authority | Next architectural gap |
| Device evidence | Not tested | Public project claims or issue history do not establish parity for this project | No compatibility claim |

This comparison is based on architecture and source behavior. It does not treat README claims, repository size or source presence as application compatibility evidence.

## Test result

- Atomic catalog aggregate self-test: PASS.
- Immutable revision publication source gate: PASS.
- Catalog-before-delete ordering gate: PASS.
- Backup-before-primary recovery ordering and primary-failure rollback tests: PASS.
- Legacy mutable-layout migration and replay test: PASS.
- Static Android-source compilation: PASS.
- Full host gate: recorded in the M4-T3 verification log.
- Android build, Emulator and physical-device tests: deferred.

## Remaining gap and next priority

M4-T3 gives the project a coherent local package transaction boundary, but it is still much narrower than the package infrastructure required for VA/NBB-class application compatibility. The next priority is a Binder-owned package service with cross-process serialization and inaccessible management controls, followed by virtual PackageManager state breadth, split APKs and cache lifecycle management.
