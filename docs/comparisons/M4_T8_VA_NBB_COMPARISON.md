# M4-T8 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-28

## Iteration scope

M4-T8 expands virtual PackageManager queries, Intent resolution, enabled-state policy and install metadata. This comparison is based on repository source and host-side evidence. It does not treat README claims from any project as independent device compatibility evidence.

## New capability in this iteration

| Area | Controlled Sandbox M4-T8 result | Evidence |
|---|---|---|
| Typed filters | Revision-bound AIDL snapshots carry priority, action, category, URI and MIME declarations | Contract classes, state builder and Guest mapper |
| Resolve | Guest-local Activity/Service/Receiver matching with deterministic ordering and default/disabled flags | Virtual package metadata and query self-test |
| Provider query | Multi-authority lookup and Guest-owned authority fail-closed behavior | Metadata and PackageManager handler |
| Enabled state | Per-virtual-user package and component overrides in Catalog v5 | Catalog, policy state and Package Service |
| Install metadata | First-install/update times, virtual installer, PackageInfo flags and declared shared libraries | Record, package snapshot and query model |
| Isolation | Explicit Guest-target no-match does not delegate to host PackageManager | Invocation handler and self-test |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T8 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Package state authority | Typed Binder snapshot and atomic per-user policy | Mature engines generally own a broader virtual PMS data model | State consistency is strong; breadth remains behind |
| Intent resolution | Bounded action/category/data/MIME matching and deterministic ordering | VA/NBB-class implementations have more Android-version-specific resolver behavior | Core source gap reduced; exact platform parity unproven |
| Component enabled state | Package and declared-component overrides are persisted per virtual user | Mature engines usually cover package stopped/hidden/suspended and more state transitions | Basic enabled-state parity only |
| PackageInfo/ApplicationInfo | Version, timestamps, requested permissions and selected component flags | Mature engines expose substantially more fields, flags, signing and visibility behavior | Still narrower |
| Provider lookup | Multiple authorities and disabled-state handling | Mature engines integrate deeper with virtual Provider/PMS authority tables | Bounded source path is competitive, device behavior unknown |
| Shared libraries | Declaration names retained in revision snapshot | Mature engines resolve framework libraries, optional/required semantics and linker namespaces | Major gap remains |
| Cross-package queries | Guest-target isolation plus bounded host/system fallback | Mature engines maintain virtual installed-package lists and package visibility rules | Material gap remains |
| Android adaptation | No device evidence | VA/NBB contain more API/OEM-specific branches | Reliability cannot be compared |
| Code governance | Typed contracts, deterministic tests and explicit evidence boundary | Upstream quality varies by branch/fork | Controlled Sandbox strength in this scope |

## Test result

- Virtual PackageManager query/resolve gate: PASS.
- Static Android-source compilation and new self-tests: PASS.
- Complete host verification gate: PASS after final source review.
- Emulator and physical-device tests: deferred by current scope.

## Current completion evidence

The evidence matrix now tracks 59 capabilities:

- Source: 56 complete, 3 partial, 0 missing; weighted **97.5%**.
- Production wiring: 52 wired, 5 partial, 1 blocked, 1 not applicable; weighted **94.0%**.
- Device evidence: 0 verified, 57 not tested, 1 blocked, 1 not applicable; weighted **0.0%**.

These percentages measure repository-defined evidence coverage. They do not measure APK launch rate, application compatibility or parity with VA/NBB.

## Remaining gap and next priority

M4-T8 closes a major source-level gap in Guest-local package query and resolve behavior. VA/NBB remain ahead in full virtual PMS breadth, package visibility, preferred activities, domain verification, system/virtual list merging, shared-library resolution, Android-version adaptation, 32-bit execution and accumulated device compatibility evidence.
