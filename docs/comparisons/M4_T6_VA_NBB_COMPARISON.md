# M4-T6 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-27

## Iteration scope

M4-T6 introduces staged multi-APK installation, Split dependency validation, immutable artifact-set revisions and Split-aware Guest loading. The comparison is source-based. No device compatibility claim is made for Controlled Sandbox, VA or NBB.

## New capability in this iteration

| Area | Controlled Sandbox M4-T6 result | Evidence |
|---|---|---|
| Install session | Persisted `OPEN/SEALED` Binder-owned session with retry and abandon | `PackageInstallSessionStore`, `SandboxPackageLifecycle` |
| Product entry | Main picker accepts multiple APK files and commits them atomically | `MainActivity`, `PackageServiceClient` |
| Split model | Base, Feature and Configuration Split metadata with dependency checks | manifest model, `PackageArtifactRecord` |
| Publication | Immutable Base/Split/native directory under one deterministic Revision | `ApkImportManager`, `PackageStorageLayout` |
| Runtime integrity | Broker and Guest verify every artifact and the full set digest | `PackageRevisionSetVerifier` |
| Runtime loading | Dependency-ordered class path, resources and split Context metadata | Guest runtime classes |
| Compatibility | Catalog v1/v2 remains readable; single APK keeps legacy Revision identity | Catalog v3 and revision tests |
| Contract | Typed artifact snapshots and explicit session methods; no management `Bundle` | `sandbox-contract` |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T6 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Staged installation | Core state machine and product multi-select path now exist | Mature engines generally have broader install/update entry points and package-parser integration | Gap reduced, still narrower |
| Split APK validation | Package/version/signer/dependency checks and immutable publication | Mature engines have years of Android PackageParser and version-specific compatibility work | Source model is credible; platform breadth behind |
| Split class/resources | Dependency-ordered paths feed class loader and resources | VA/NBB-class engines generally handle more framework and cache details | Basic path exists; device behavior unknown |
| Revision integrity | Per-artifact digest plus complete set digest, checked at multiple boundaries | Upstream projects vary in explicit artifact-set integrity design | Maintainability/integrity strength in this scope |
| Shared libraries | Declaration names retained | Mature engines usually model more PackageManager/shared-library semantics | Materially behind |
| Dex/oat lifecycle | Not implemented | Mature engines commonly manage optimized code and update invalidation | Major gap |
| PackageInstaller parity | No streaming, incremental, rollback history or install constraints | Mature implementations cover a wider install surface | Behind |
| 32-bit execution | Not implemented | VA/NBB variants commonly target broader ABI combinations | Behind |
| Android-version adaptation | No device evidence | Upstream projects contain more API/OEM compatibility code | Cannot compare reliability |
| Hostile Guest isolation | Shared application UID remains | Same-UID virtualization retains trust limitations | No security-parity claim |

## Test result

- Split/install-session source gate: PASS.
- Static Android-source compilation and new self-tests: PASS.
- Complete host verification gate: PASS after final stage review.
- Emulator and physical-device tests: deferred by current scope.

## Current completion evidence

The evidence matrix now tracks 49 capabilities:

- Source: 46 complete, 3 partial, 0 missing; weighted **96.9%**.
- Production wiring: 42 wired, 5 partial, 1 blocked, 1 not applicable; weighted **92.7%**.
- Device evidence: 0 verified, 47 not tested, 1 blocked, 1 not applicable; weighted **0.0%**.

These percentages measure repository-defined evidence coverage. They do not measure APK launch rate, application compatibility or feature parity with VA/NBB.

## Remaining gap and next priority

M4-T6 closes the most obvious modern multi-APK installation gap. The next dominant differences from mature VA/NBB-class engines are permission capability mediation, PackageManager breadth, dex/oat lifecycle, system-service API coverage, 32-bit execution and Android-version/OEM adaptation. Those gaps are larger than the remaining install-session source work.
