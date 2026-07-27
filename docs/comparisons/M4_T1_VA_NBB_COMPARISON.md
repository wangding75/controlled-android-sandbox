# M4-T1 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-27

## Iteration scope

M4-T1 hardens the Guest Context and Guest class-loading boundary. Emulator and physical-device compatibility are not part of this iteration.

## New capability in this iteration

| Area | Controlled Sandbox M4-T1 result | Evidence |
|---|---|---|
| Host implementation visibility | Guest loader denies all internal project classes except stable Binder contracts | `GuestClassLoader`, boundary self-test and source gate |
| Host Context unwrap | Standard `getBaseContext()` returns the Guest Context | `GuestContextBoundarySelfTest` |
| Private storage | Principal internal storage APIs resolve below the virtual-instance root | Context boundary self-test |
| External-style paths | Guest external files/cache, OBB and media paths resolve below the virtual-instance root | Context boundary self-test |
| Context derivation | Host-package/split acquisition fails closed; supported derivations remain Guest-scoped | Context boundary self-test |
| Device-protected storage | Explicitly unsupported instead of silently delegating to host storage | Source and self-test |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T1 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Guest Context path isolation | Expanded from a small subset to the principal directory APIs | Mature virtual-app engines generally mediate substantially more Context and framework behavior | Local gap reduced; breadth gap remains |
| Host implementation class visibility | Explicit deny policy added | Mature engines rely on broad runtime/framework indirection rather than one class-loader rule alone | Useful defense-in-depth, not parity |
| PackageManager and system services | Partial | VA/NBB codebases expose broader hook/proxy surfaces and longer Android adaptation history | Material gap remains |
| Process/UID security boundary | Guest still shares host process UID | VA/NBB are also application virtualization frameworks, not equivalent to a hardware VM or separate Android user | No malicious-APK security claim |
| Device compatibility evidence | 0% by current project decision | Public projects have device-oriented adaptation claims and issue history | No compatibility comparison can be concluded |

The comparison is architectural and source-based. It does not infer an application compatibility percentage from file counts or README claims.

## Test result

- Guest class-loader host-boundary test: PASS.
- Guest Context storage/unwrap test: PASS.
- Static Android source compilation: PASS.
- Repository Guest-boundary source gate: PASS.
- Full host verification: recorded by the iteration verification log.
- Android SDK/NDK build: not executed in the current environment.
- Emulator and physical-device tests: deferred by project decision.

## Remaining gap and next priority

M4-T1 closes a direct implementation leak but does not yet provide the framework breadth associated with VA/NBB. The next high-value work is immutable APK-revision Session binding and transactional package lifecycle, followed by privileged-control authorization and broader system-service virtualization.
