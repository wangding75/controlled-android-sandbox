# M4-T3 — transactional package lifecycle and immutable revision publication

## Scope

This source-only iteration hardens APK import, upgrade, clone, status and deletion around one authoritative package catalog. Android SDK/NDK build, Emulator, physical-device and third-party APK execution remain intentionally deferred.

## Defects closed

The previous product path had four consistency hazards:

1. Package metadata and virtual-instance metadata were written to two independent files.
2. The delete action mutated in-memory state, swallowed persistence failures and then deleted instance/package files anyway.
3. An upgrade replaced one mutable package directory, while active or persisted state could still reference the old bytes.
4. `RecoverableFileStore.write()` wrote the primary before the backup. A backup failure could report an operation failure after new primary state was already committed, allowing callers to roll back files still referenced by metadata.

## Implemented

### One atomic catalog

- Added immutable `SandboxCatalogState`, containing package records and virtual instances as one validated aggregate.
- The aggregate rejects duplicate packages, duplicate instance keys, orphan instances, invalid virtual-user IDs and incomplete package revision metadata.
- Package and instance order is canonicalized before persistence.
- Added `SandboxCatalogRepository`, which writes one `sandbox-catalog.json` and one last-known-good copy through `RecoverableFileStore`.
- Existing `sandbox-packages.json` and `sandbox-instances.json` are loaded only as migration inputs when no catalog exists. Missing default instances and orphan legacy instances are normalized during migration.
- Legacy mutable `packages/<package>/base.apk` and `lib/` payloads are copied into the canonical SHA-256 revision directory before the first catalog commit. The migration is idempotent, verifies the APK digest, rejects paths outside the managed package root and refuses symbolic-link traversal.

### Immutable APK/native publication

- APK payloads are published under:

  `files/packages/<package>/revisions/<apk-sha256>/base.apk`

- Extracted native libraries live beside the APK in the same immutable revision directory.
- A revision directory that already exists must contain a `base.apk` matching the directory SHA-256 or import fails closed with `IMMUTABLE_REVISION_DIRECTORY_MISMATCH`.
- Publication first requests an atomic directory move and falls back to a same-filesystem move when the platform does not support `ATOMIC_MOVE`.
- Existing immutable revisions are accepted only when both the APK digest and extracted native-file digest map match the freshly staged revision.
- Catalog load requires canonical app-private revision paths and existing APK/native payloads.
- Tree deletion treats symbolic links as links and does not traverse them; managed package paths reject symbolic links in every existing path segment.

### Transaction ordering

Import/upgrade now follows this order:

1. Parse, verify, hash and extract into a private staging directory.
2. Publish a new immutable revision directory.
3. Construct and validate the next catalog aggregate.
4. When an existing package revision changes, stop every catalog instance through the runtime
   death barrier, including the old ABI companion route.
5. Atomically switch the catalog.
6. Remove unreferenced package revisions and interrupted `.install-*` directories.

The stop barrier is attached to every production import/install entry point. If it fails, the new
revision is deleted when unreferenced and the old catalog remains authoritative; an upgrade cannot
publish a revision while an old Guest ClassLoader or native workspace is still live.

If the catalog switch fails, the newly published revision is removed when no prior catalog record references it. Cleanup failure is attached as a suppressed exception instead of being silently discarded.

Delete now follows this order:

1. Stop the selected Runtime Session from the product action.
2. Construct and validate the next catalog aggregate.
3. Atomically switch the catalog.
4. Delete the selected instance directory.
5. Sweep package revisions and instance directories no longer referenced by the catalog.

Post-commit cleanup failure does not revert trusted metadata or report that the metadata transaction failed. It is retained as an explicit maintenance warning and retried on the next catalog load.

### Recoverable metadata ordering

`RecoverableFileStore.write()` now publishes the recovery copy before the primary. If primary publication fails synchronously, the previous backup is restored; on a first write, the uncommitted backup is removed. This prevents the caller from receiving a failure while a recoverable copy still points at files the caller is about to roll back. A process crash between backup and primary publication remains recoverable because the package revision has already been published.

### Product wiring

`MainActivity` now uses `SandboxPackageLifecycle` for import, clone, status and delete operations. It no longer writes package and instance repositories independently, mutates persisted objects in place, or deletes files after swallowing a metadata exception.

## Verification

- `SandboxCatalogStateSelfTest` executes:
  - legacy normalization and default-instance creation;
  - package revision replacement while preserving instances;
  - clone allocation;
  - immutable status update;
  - last-instance/package removal in one aggregate;
  - canonical ordering;
  - orphan and duplicate rejection;
  - legacy mutable-layout migration, idempotent replay and outside-root rejection.
- `scripts/check-package-lifecycle-transaction.py` gates:
  - single-catalog persistence;
  - catalog-before-delete ordering;
  - immutable revision paths;
  - symbolic-link-safe deletion;
  - backup-before-primary publication;
  - product use of the lifecycle authority;
  - revision stop-before-switch ordering and isolated physical-death barrier;
  - executable self-test registration.
- `tools/static_android_compile.py` compiles the production path and executes the aggregate self-test.
- The complete host verification gate runs all pre-existing checks plus the new lifecycle gate.

## Evidence boundary and remaining limits

This iteration establishes source-level transaction ordering and recoverable metadata semantics. It does not prove filesystem crash behavior on Android devices.

Known limits:

- `ATOMIC_MOVE` may be unsupported and the fallback move has weaker crash guarantees.
- Parent-directory `fsync` is not implemented, so sudden power-loss durability is not claimed.
- The catalog lock is in-process; a future independent installer process would require an OS-level file lock or a single Binder-owned package authority.
- Split APKs, install sessions, rollback history, dex/odex lifecycle, shared libraries and package-permission state remain outside this iteration.
- Guest and host still share the application UID; this is not a malicious-APK security boundary.
- Emulator and physical-device evidence remain 0% by the current project decision.

## Next priority

1. Move package lifecycle ownership behind a Binder-owned package service and add cross-process serialization.
2. Add privileged management authorization that Guest code cannot invoke.
3. Expand virtual PackageManager install/query semantics and package-permission state.
4. Add split APK and native/dex cache lifecycle support.
