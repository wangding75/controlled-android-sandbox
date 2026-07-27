# M4-T4 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-27

## Iteration scope

M4-T4 centralizes package mutation in a Binder-owned process and introduces a typed, PID/UID-bound management capability. It does not expand Android component coverage or claim device compatibility.

## New capability in this iteration

| Area | Controlled Sandbox M4-T4 result | Evidence |
|---|---|---|
| Package authority | One `:sandbox_package` service owns production package lifecycle state | `PackageManagementService` |
| Cross-process serialization | All package and instance operations share one service lock | `PackageManagementService` |
| Caller authorization | AMS-recorded main process, app UID and caller PID must match | `PackageCallerVerifier`, `ManagementCallerPolicy` |
| Capability ownership | Returned session Binder is bound to owner PID/UID and client death | `ManagementSessionGuard`, `IPackageManagementSession` |
| Guest denial | Guest/runtime/package-service processes cannot mint a management session | Authorization self-test and source gate |
| Contract quality | Typed Parcelable package/catalog/result models; no package-management `Bundle` | `sandbox-contract` package service AIDL |
| Product integration | Main and debug entrypoints use `PackageServiceClient` | `MainActivity`, `DebugCommandActivity` |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T4 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Central package service | Dedicated service and mutation serialization now exist | Mature engines normally centralize virtual package state and installation operations | Core architectural gap reduced |
| Management authorization | Explicit main-process capability and per-call PID/UID checks | Mature engines generally rely on internal Binder topology, process roles and broader permission models | Local control is clearer; device behavior unverified |
| Package contract typing | New package AIDL is fully typed | Upstream implementations vary and often carry legacy generic payloads | Current project has a maintainability advantage in this narrow area |
| Virtual PackageManager breadth | Metadata remains narrow | VA/NBB-class engines model package queries, components, permissions, signatures, shared libraries and process state more broadly | Large gap remains |
| Install sessions and splits | Not implemented | Mature package layers require multi-APK and staged install handling | Behind |
| Permission/AppOps ownership | Not yet owned by package service | Broader engines mediate permission and AppOps surfaces | Major next gap |
| Hostile Guest isolation | Shared application UID remains | VA/NBB also face same-UID and hook-boundary limitations depending on design | No security-parity claim |
| Device evidence | Not tested | Public claims do not establish parity for this project | No compatibility claim |

## Test result

- Binder-owned package-service boundary gate: PASS.
- Management authorization self-test: PASS.
- Typed Parcelable contract round-trip test: PASS.
- Existing package lifecycle transaction gate: PASS after service-client migration.
- Static Android-source compilation: PASS.
- Complete host verification gate: PASS.
- Locked JDK 17/Gradle 8.13 Android build: not executed because the current container lacks JDK 17 and the verified Gradle distribution cache.
- Emulator and physical-device tests: deferred.

## Remaining gap and next priority

M4-T4 establishes the process ownership pattern needed for a VA/NBB-class package layer, but the represented package state remains much narrower. The next iteration should place virtual PackageManager query semantics, package permission state and AppOps decisions behind the same Binder authority before adding split APK installation sessions.
