# M4-T2 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-27

## Iteration scope

M4-T2 binds every live Runtime Session to verified APK bytes and prevents stale Session reuse after an APK upgrade. It does not add new Android component or system-service virtualization coverage.

## New capability in this iteration

| Area | Controlled Sandbox M4-T2 result | Evidence |
|---|---|---|
| APK byte identity | Broker and Guest independently verify SHA-256 | `ApkRevisionVerifier` and executable self-test |
| Session identity | Immutable package revision stored in every `GuestSession` | Domain model and transition tests |
| Same-revision request | Existing live Session may be reused | `SessionRegistry` test |
| Different-revision request | Live Sessions for the virtual instance are stopped before new preparation | Session revision policy and Broker wiring |
| Cached prepare state | Cached spec revision must match the Session revision | Broker source gate |
| Optimized code | Revision-specific code-cache path | Guest Runtime source |
| Diagnostics | Session response contains package revision | Broker Session bundle |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T2 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Stale Runtime prevention | Explicit SHA-bound Session policy now exists | Mature engines also need package replacement and process invalidation behavior | Core local defect closed at source level |
| Package install/update lifecycle | Existing importer still replaces package state across multiple resources without one durable transaction | Mature VA/NBB-style package managers have broader install/update lifecycle machinery | Material gap remains |
| Multi-process revision consistency | All live processes for one virtual instance are selected for replacement | Mature engines have longer multi-process adaptation history | Source design improved; Android behavior unverified |
| Code/resource cache invalidation | Revision path added | Mature engines maintain broader dex/native/resource cache management | Partial parity only |
| Device compatibility | Not tested | Public projects have device-oriented adaptation history | No parity claim |

This report compares source behavior and architecture only. README claims, file counts and issue history are not treated as proof of compatibility.

## Test result

- Domain Session revision binding: PASS.
- Live Session mismatch policy: PASS.
- APK mutation rejection: PASS.
- Static Android-source compilation: PASS.
- Full host gate: recorded in the iteration verification log.
- Android build and device tests: deferred.

## Remaining gap and next priority

The Runtime can now identify an APK revision correctly, but the importer still needs immutable revision publication and atomic metadata switching. M4-T3 should make package import, upgrade and deletion recoverable across APK files, extracted native libraries, package metadata and virtual-instance metadata.
