# M4-T7 — Runtime permission capability and host-backed access policy

Date: 2026-07-28

## Scope

M4-T7 connects per-virtual-user permission policy to real host-package capability state and adds a persisted runtime-permission workflow. It retains the M4-T4 Binder-owned package authority and the M4-T5 virtual PackageManager/AppOps model. Emulator and physical-device validation remain outside this iteration.

## Implemented

### Catalog v4 permission workflow

The atomic package Catalog now persists bounded `permissionRequests` and `permissionAudit` collections in schema version 4 while remaining able to read versions 1–3. Requests are bound to package, virtual user, permission, request code, Runtime Session ID and Guest generation.

The state model supports:

- `PENDING`, `GRANTED`, `DENIED` and `CANCELLED` request states;
- idempotent replay for the same Session/generation/request code;
- cancellation when a newer Guest generation supersedes a pending request;
- grant refusal when the host application does not actually hold the capability;
- atomic permission/AppOps updates on resolution and revocation;
- pending-request cancellation on policy reset;
- cancellation and audit of pending requests when the package APK Revision changes;
- request and audit cleanup when a virtual instance is deleted;
- bounded history with linked audit pruning;
- typed query of pending requests and recent audit records.

### Runtime-Broker-only Binder capability

`PackageManagementService` now issues `IRuntimePermissionSession` only after the caller registers and presents the Runtime role Binder capability. The role is bound to the registering UID/PID, generation and Binder death, while each session is separately bound to its client Binder. Management capability holders cannot substitute for the Runtime role and Runtime sessions cannot invoke host-management operations. The shared-UID route remains defense in depth; isolated UID execution is required for hostile Guest code.

The typed runtime permission protocol contains no business `Bundle` payloads.

### Host-backed effective grants

`HostPermissionStateResolver` reads system-owned package metadata and `PackageManager.checkPermission()` for the host package. `VirtualPackageStateBuilder` calculates an effective grant from:

1. the permission declared by the Guest package;
2. the virtual-user decision;
3. the permission declared by the host package;
4. the host package's current real grant;
5. the controlled capability/AppOps mapping.

A virtual `GRANTED` value alone cannot manufacture a Linux-UID or Android permission that the host package does not possess. A configured `AppOps=ALLOWED` value is also clamped to a non-allowing mode whenever the linked effective permission is denied; AppOps cannot bypass the effective-permission decision.

### Activity callback bridge

The production Activity bridge processes Android permission callback results through:

1. a Runtime Broker request record;
2. Package Service verification of the actual host permission state;
3. Catalog resolution and audit;
4. a refreshed immutable package-state snapshot;
5. effective grant results delivered to the Guest Activity.

The existing callback bridge creates the persisted pending record immediately before verified resolution. A universal interception point before every framework/OEM permission UI path is not claimed.

### Same-generation policy refresh

After a successful permission result, the active Guest generation validates package name, virtual-user identity and APK revision, then replaces:

- `VirtualPermissionPolicy` state;
- `SandboxAppOpsPolicy` state;
- the bounded camera/location service-acquisition gate;
- the Session's current package-state snapshot.

Permission and AppOps policy replacement uses immutable snapshots so individual reads do not observe a partially replaced map/set. Revocation affects subsequent checks and subsequent camera/location service acquisition without restarting the Guest process.

### Bounded capability gate

`GuestContext.getSystemService()` fails closed for camera and location service acquisition unless an effective permission exists. This is a compatibility and accidental-access boundary. It does not revoke an already acquired service object, intercept every static/native API, or create a hostile-code security boundary inside the shared host UID.

## Tests and gates

New or expanded host-side evidence includes:

- request idempotency and generation superseding;
- host-capability-required grant resolution;
- virtual-user isolation;
- policy reset and revocation behavior;
- instance deletion cleanup;
- typed request/audit Parcelable round trips;
- Runtime Broker caller/session ownership checks;
- same-generation permission and AppOps replacement;
- same-generation camera service acquisition grant/revocation checks;
- architecture gate `check-runtime-permission-workflow.py`;
- static Android-source compilation of all new AIDL/API surfaces.

## Evidence boundary

This iteration proves source implementation, host-side state transitions, typed Binder wiring, static Android compilation and production entry-point wiring. It does not prove:

- real Android runtime-permission UI and callback behavior;
- one-time permissions, approximate location, auto-reset or permission groups;
- role management, special app access, signature/privileged permissions or OEM permission managers;
- deep camera, microphone, location or media service proxy behavior;
- revocation of service objects already acquired by Guest code;
- hostile APK isolation while Guest and host share one application UID;
- compatibility with any third-party application.

## Next priority

1. Expand PackageManager query/resolve and enabled-state semantics.
2. Add capability-specific camera, microphone and location service proxies rather than handle-only gates.
3. Add dex/oat cache ownership and revision cleanup.
4. Add native shared-library namespace and dynamic-loader policy.
5. Split `RuntimeBrokerService` responsibilities before further system-service expansion.
