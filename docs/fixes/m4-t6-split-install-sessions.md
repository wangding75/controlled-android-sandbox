# M4-T6 — Split APK, staged install sessions and multi-artifact revisions

Date: 2026-07-27

## Scope

M4-T6 adds source-level support for installing and loading one package revision composed of a Base APK plus Feature and Configuration Split APKs. It preserves the M4-T3 immutable publication model and the M4-T4 Binder-owned package authority. Emulator and physical-device validation remain outside this iteration.

## Implemented

### Staged Binder-owned install sessions

`PackageManagementService` exposes typed management operations to create, append to, commit and abandon an install session. Session state is persisted under app-private `files/install-sessions/<id>` storage and is owned by the package-service process.

A session has explicit `OPEN` and `SEALED` states. It enforces:

- a maximum of 256 APK artifacts;
- 1.5 GiB per artifact and 3 GiB per install set;
- ZIP/APK magic validation before state advancement;
- atomic state-file replacement;
- deterministic artifact numbering;
- rejection of symbolic-link state/artifact paths;
- restart recovery and retry after a failed commit;
- stale-session cleanup after 24 hours.

After the package Catalog commit succeeds, failure to delete the staging directory is reported as a maintenance warning. It does not convert an already committed installation into a false failure.

### Product import wiring

The main APK picker enables `Intent.EXTRA_ALLOW_MULTIPLE`. A single selected APK retains the direct import path. Multiple selected APKs use the staged install-session API and are committed as one package revision. Failed multi-selection imports abandon the staging session.

### Split manifest and artifact model

The binary manifest model now retains:

- `split`;
- `configForSplit`;
- `usesSplit`;
- `isFeatureSplit`;
- `uses-library` names.

`PackageArtifactRecord` distinguishes `BASE`, `FEATURE` and `CONFIG` artifacts. Split names and dependency names are restricted to safe identifier characters. Exactly one Base APK is required; package name, version code and signing identity must match across every artifact.

The importer rejects duplicate split names, missing `usesSplit` dependencies, missing configuration targets and unsafe split identifiers.

### Dependency-ordered runtime loading

`PackageArtifactOrder` computes a deterministic dependency-first order:

1. Base APK;
2. Feature dependencies before dependent Feature APKs;
3. Configuration APKs after their target Base or Feature APK.

Missing dependencies and dependency cycles fail closed. This order feeds the Guest class path, `AssetManager`, `ApplicationInfo.splitSourceDirs` and `createContextForSplit()`.

### Immutable multi-artifact revision

Every artifact has its own SHA-256. A multi-APK package revision is the deterministic SHA-256 of canonical artifact metadata and artifact digests. A single-APK package retains its historical Base APK SHA-256 as the revision ID, keeping schema v1/v2 Catalog and directory layouts compatible.

The complete revision is checked by:

- the importer before publication;
- Catalog layout validation on load/save;
- Runtime Broker before Session allocation;
- Guest Runtime before class/resource loading.

Changing any Split APK invalidates the complete package revision.

### Catalog and typed contract

Catalog schema is now version 3 and remains able to read schema versions 1 and 2. Package records contain:

- Base APK digest;
- full artifact list and paths;
- artifact types and dependencies;
- shared-library declaration names.

`PackageArtifactSnapshot` carries the same typed metadata over Binder. Package-management AIDL remains free of business `Bundle` payloads.

## Tests and gates

New or expanded host-side evidence includes:

- persisted install-session restart, seal, reopen, abandon and invalid-artifact tests;
- dependency ordering, missing dependency, cycle and unsafe-name tests;
- importer/runtime revision-algorithm compatibility test;
- single-APK backward-compatibility test;
- Split APK mutation rejection test;
- typed Parcelable round-trip checks;
- `check-split-install-sessions.py` architecture and production-wiring gate;
- updated immutable-revision, Catalog and lifecycle gates.

## Evidence boundary

This iteration proves source implementation, static Android compilation, host-side state behavior and production entry-point wiring. It does not prove:

- Android document-provider multi-selection grants across OEMs;
- real `PackageManager` parsing/signature behavior for Split APK files;
- ART class loading or `AssetManager` resource precedence for Split APKs;
- install behavior for App Bundles, APKM/XAPK archives or Play Feature Delivery;
- required/optional `uses-library` resolution;
- dex/odex cache lifecycle or rollback;
- Android PackageInstaller parity;
- compatibility with any third-party application.

## Next priority

1. Host-capability-aware permission broker and runtime permission workflow.
2. Expanded virtual PackageManager query/resolve semantics and enabled-state controls.
3. Dex/oat cache ownership and revision cleanup.
4. Native shared-library namespace and dynamic-loader policy.
5. Runtime Broker responsibility split before broader system-service coverage.
