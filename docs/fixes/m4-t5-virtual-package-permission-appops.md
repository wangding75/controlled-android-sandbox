# M4-T5 — Virtual package, permission and AppOps state

## Scope

This source-only iteration moves the active Guest package view, per-virtual-user permission decisions and bounded AppOps decisions behind the Binder-owned package authority created in M4-T4. Emulator, physical-device, ADB and third-party APK execution remain deferred.

## Defects closed

Before M4-T5, the runtime reconstructed package metadata locally from the APK and treated a manifest-declared permission as effectively granted. The framework proxies rewrote Guest identity but did not consume one authoritative, revision-bound policy snapshot. That created four material risks:

1. Package metadata could diverge between package management and the active Guest process.
2. A permission declaration could be confused with an explicit virtual-user grant decision.
3. Permission and AppOps changes had no atomic persistence with the package/instance catalog.
4. A Guest could query the host package through selected PackageManager paths and escape the intended virtual identity view.

## Implemented

### Binder-owned virtual package snapshot

`PackageManagementService` now builds `VirtualPackageStateSnapshot` from the trusted imported APK revision and the package/virtual-user policy stored in the catalog. The typed snapshot contains:

- package name and virtual user ID;
- APK SHA-256 and version code;
- application class and declared process information;
- Activity, Service, Receiver and Provider metadata;
- declared permissions and effective virtual decisions;
- bounded AppOps modes.

`RuntimeClient` obtains the snapshot from `PackageServiceClient` before a Guest prepare/launch request and rejects an APK SHA mismatch. `GuestPackageSpec` revalidates package name, virtual user and SHA-256 before accepting the snapshot. `GuestRuntimeEnvironment` maps that immutable snapshot into framework metadata and policy objects; it no longer reparses the active package manifest as a second runtime authority.

### Atomic per-user policy state

`SandboxCatalogRepository` uses schema version 2 and persists `SandboxPolicyState` entries in the same recoverable catalog as packages and virtual instances. Schema version 1 remains readable with an empty policy list.

Policy state is keyed by package name and virtual user ID. It supports:

- permission decisions: `DEFAULT`, `GRANTED`, `DENIED`;
- AppOps modes: `DEFAULT`, `ALLOWED`, `IGNORED`, `ERRORED`;
- reset to defaults;
- automatic policy removal when the owning virtual instance is deleted;
- referential-integrity validation that rejects orphan policy rows.

All mutations are serialized by `PackageManagementService` and committed through the existing atomic catalog path.

### Framework enforcement

`GuestIdentity` now carries `VirtualPermissionPolicy` and `SandboxAppOpsPolicy`.

- `PackageManagerInvocationHandler.checkPermission` returns the virtual decision for the active Guest.
- Direct PackageManager queries targeting the host package are hidden or denied rather than delegated.
- The permission service proxy handles bounded check-style calls locally and avoids calling the host service for an explicit virtual decision.
- The AppOps proxy handles bounded integer/boolean check, note and start-style calls locally and returns the virtual mode.
- Identity rewriting remains the fallback for methods outside the explicit policy surface.

### Typed Binder contract

Added typed Parcelable contracts:

- `VirtualComponentSnapshot`;
- `VirtualPermissionSnapshot`;
- `PackageAppOpSnapshot`;
- `VirtualPackageStateSnapshot`.

`IPackageManagementSession` gained typed query/mutation methods and still contains no `Bundle` business payloads. `PackageServiceResult` carries an optional typed package-state result.

## Compatibility behavior

A manifest-declared permission with no explicit stored override currently resolves as granted inside the virtual policy model. This preserves the prior source behavior while allowing an explicit per-user denial. It does not grant the host Linux UID a real Android permission. If the host manifest or runtime permission state lacks that permission, the Android platform may still deny the underlying operation.

This default is transitional. A later permission broker must distinguish install-time, runtime, signature, privileged, role, special-access and host-capability availability before production compatibility can be claimed.

## Evidence and gates

- `SandboxCatalogStateSelfTest` verifies per-user isolation, permission/AppOps persistence, reset, orphan rejection and atomic cleanup on instance deletion.
- `PackageServiceContractSelfTest` verifies Parcelable round trips for package state, components, permissions and AppOps.
- `FrameworkIdentityProxySelfTest` verifies explicit permission denial, default declared-permission behavior, host-package hiding and AppOps interception without host delegate calls.
- `check-virtual-package-state.py` enforces the typed AIDL, catalog schema, package-service ownership, revision-bound runtime transport and framework policy wiring.
- `check-contracts.py` now prevents the package-state protocol from regressing to `Bundle` payloads.

## Evidence boundary

This iteration proves source structure, host-side policy behavior, typed transport and production wiring. It does not prove:

- Android PackageManager/PermissionManager/AppOps Binder signatures across API levels;
- that a host permission is available to satisfy an allowed virtual decision;
- runtime permission dialogs or user-consent behavior;
- signature, privileged, role or special-access permission semantics;
- complex AppOps return objects, attribution chains, proxies or asynchronous callbacks;
- process restart and snapshot refresh on a real Android system.

The host and Guest still share the application Linux UID. M4-T5 improves identity consistency and policy mediation but does not create a hostile-code security boundary.

## Next priority

1. Add split APK and staged install-session state with immutable multi-artifact revisions.
2. Add a host-capability-aware permission broker and explicit runtime grant workflow.
3. Expand virtual PackageManager signature, shared-library, enabled-state and cross-package query semantics.
4. Broaden AppOps method/signature coverage with API-level policy tables.
5. Reduce `RuntimeBrokerService` responsibilities before adding more system-service mediation.
