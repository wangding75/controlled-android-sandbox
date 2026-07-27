# M4-T5 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-27

## Iteration scope

M4-T5 creates one Binder-issued, APK-revision-bound package/permission/AppOps snapshot for each virtual package and virtual user. It strengthens source consistency and framework mediation; it does not claim Android-device compatibility or feature parity with VA/NBB.

## New capability in this iteration

| Area | Controlled Sandbox M4-T5 result | Evidence |
|---|---|---|
| Package-state authority | Package Service builds the active package/component/policy snapshot | `PackageManagementService`, `VirtualPackageStateBuilder` |
| Runtime binding | Snapshot is bound to package, virtual user and APK SHA-256 | `RuntimeClient`, `GuestPackageSpec` |
| Permission state | Per-user `DEFAULT/GRANTED/DENIED` state persists in atomic Catalog v2 | `SandboxPolicyState`, `SandboxCatalogRepository` |
| AppOps state | Per-user `DEFAULT/ALLOWED/IGNORED/ERRORED` modes persist with the instance | `SandboxPolicyState`, `SandboxAppOpsPolicy` |
| Framework use | PackageManager, PermissionManager and bounded AppOps calls consume the snapshot | framework invocation handlers |
| Host identity hiding | Direct PackageManager queries for the host package are rejected or hidden | `PackageManagerInvocationHandler` |
| Contract quality | Four typed Parcelable models and typed mutation/query methods; no package-management `Bundle` | `sandbox-contract` |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T5 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Single package-state authority | Package/runtime views now originate from one Binder authority | Mature engines maintain a central virtual package model | Architectural gap reduced |
| Package query breadth | Application/package/components/basic resolve and installed-list views exist | VA/NBB-class engines generally cover more PackageManager signatures, signatures, shared libraries, enabled state and cross-package behavior | Still materially behind |
| Permission persistence | Explicit per-package/per-user decisions are atomic and revision-bound | Mature engines mediate broader permission APIs and Android-version differences | Core state now exists; semantics remain narrow |
| Host capability mediation | Virtual grant does not yet check or acquire host permission | Mature engines still depend on host capabilities but normally include broader proxy and request flows | Major gap remains |
| AppOps | Bounded integer/boolean check/note/start surfaces consume virtual modes | Mature implementations cover more methods, op encodings, attribution and API changes | Behind |
| Contract design | Typed snapshots and explicit result envelope | Upstream codebases often include older generic or version-coupled IPC forms | Maintainability advantage in this narrow area |
| Split APK/install sessions | Not implemented | Required for broad modern APK compatibility | Behind |
| 32-bit execution | Not implemented | NBB/VA variants commonly target broader ABI combinations | Behind |
| Hostile Guest isolation | Shared application UID remains | Same-process/UID virtualization designs retain substantial trust limitations | No security-parity claim |
| Device evidence | 0% | Upstream public claims cannot validate this project | No compatibility comparison possible |

## Test result

- Virtual package-state source gate: PASS.
- Typed contract gate: PASS.
- Catalog v1-to-v2 compatibility and policy integrity tests: PASS.
- Permission and AppOps framework proxy tests: PASS.
- Static Android-source compilation: PASS.
- Complete host verification gate: PASS.
- Emulator and physical-device tests: deferred by current development scope.

## Current completion evidence

The repository evidence matrix now tracks 44 capabilities:

- Source: 41 complete, 3 partial, 0 missing; weighted **96.6%**.
- Production wiring: 37 wired, 5 partial, 1 blocked, 1 not applicable; weighted **91.9%**.
- Device evidence: 0 verified; weighted **0.0%**.

These are repository evidence metrics. They are not APK launch-rate or compatibility percentages.

## Remaining gap and next priority

M4-T5 closes the absence of authoritative package permission/AppOps state, but it does not yet reproduce Android's complete package and permission semantics. The next high-value gap relative to VA/NBB is modern installation support: split APK sets, staged install sessions, shared libraries and immutable multi-artifact revisions. Permission work must then add host-capability awareness and real runtime-consent mediation before device compatibility can be judged.
