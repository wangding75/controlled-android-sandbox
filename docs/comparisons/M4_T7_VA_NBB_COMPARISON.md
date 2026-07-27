# M4-T7 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-28

## Iteration scope

M4-T7 adds a host-capability-aware runtime permission workflow, permission audit, a Runtime-Broker-only Binder capability and live Guest policy refresh. This comparison is based on repository source and host-side evidence. No device compatibility claim is made for Controlled Sandbox, VirtualApp or NewBlackbox.

## New capability in this iteration

| Area | Controlled Sandbox M4-T7 result | Evidence |
|---|---|---|
| Persistent workflow | Catalog v4 stores request, resolution, cancellation, revocation and bounded audit history | Catalog state/repository and workflow self-test |
| Binder authority | Separate typed PID/UID-bound Runtime Broker permission Session | Package Service, caller verifier and AIDL |
| Host capability | Effective grant requires the host package's real Android grant | Host resolver and package-state builder |
| Runtime callback | Host Activity callback is verified and resolved before reaching Guest Activity | Stub Activity, Runtime Broker and Package Service |
| Live policy | Current Guest generation replaces permission, AppOps and bounded service-acquisition state | Guest runtime and framework policy classes |
| Failure boundary | Unknown/unauthorized paths fail closed; virtual grant cannot create a missing host permission | Workflow and authorization tests |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T7 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Permission state model | Explicit per-user policy, request state and audit are now persisted atomically | Mature engines generally virtualize a broader permission/package surface | State quality improved; breadth behind |
| Host permission mediation | Effective grant explicitly includes real host capability | Mature engines commonly rely on host manifest/UID capability plus compatibility hooks | Gap reduced for bounded permissions |
| Runtime permission UI | Callback resolution exists; no universal pre-request UI broker | Mature engines/forks contain more Android-version-specific permission handling | Material gap remains |
| AppOps integration | Resolution/revocation updates linked virtual AppOps and an explicit ALLOWED mode is clamped when the effective permission is denied | Mature engines cover more op names, attribution and service signatures | Narrower coverage, coherent permission boundary |
| Capability proxying | Camera/location service acquisition is gated | VA/NBB-class engines generally hook more service calls and framework variants | Far behind in depth |
| PermissionController parity | Not implemented | Mature stacks have broader runtime/system integration, though implementation quality varies | Major gap |
| Auditability | Typed persisted request/audit history with bounded retention | Upstream projects vary in explicit audit design | Maintainability strength in this scope |
| Security isolation | Runtime Binder caller checks and fail-closed paths exist, but host and Guest share UID | Same-UID virtualization cannot provide a strong hostile-code boundary | No security-parity claim |
| Android-version adaptation | No device evidence | VA/NBB contain more version/OEM-specific branches | Cannot compare reliability |

## Test result

- Runtime-permission architecture/workflow gate: PASS.
- Static Android-source compilation and new self-tests: PASS.
- Complete host verification gate: PASS after final source review.
- Emulator and physical-device tests: deferred by current scope.

## Current completion evidence

The evidence matrix now tracks 54 capabilities:

- Source: 51 complete, 3 partial, 0 missing; weighted **97.2%**.
- Production wiring: 47 wired, 5 partial, 1 blocked, 1 not applicable; weighted **93.4%**.
- Device evidence: 0 verified, 52 not tested, 1 blocked, 1 not applicable; weighted **0.0%**.

These percentages measure repository-defined evidence coverage. They do not measure APK launch rate, application compatibility or feature parity with VA/NBB.

## Remaining gap and next priority

M4-T7 closes the previous mismatch where a virtual grant could be reported independently of the host package's real capability. The dominant remaining differences from mature VA/NBB-class engines are full PermissionController behavior, capability-specific service proxies, PackageManager breadth, dex/oat lifecycle, native linker coverage, 32-bit execution and Android-version/OEM adaptation.
