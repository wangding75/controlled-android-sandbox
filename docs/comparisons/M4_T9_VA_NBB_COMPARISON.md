# M4-T9 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-28

## Iteration scope

M4-T9 adds bounded Camera, Location and microphone-related Binder method mediation, live resource cleanup, capability audit, Attribution-aware AppOps handling and a permission coordinator extracted from the central Broker. Comparison is based on source and host-side tests. No project README claim is treated as device compatibility evidence.

## New capability in this iteration

| Area | Controlled Sandbox M4-T9 result | Evidence |
|---|---|---|
| Capability policy | Effective permission and AppOps are combined per Guest generation | `CapabilityAccessPolicy` and proxy tests |
| Camera | Connect/open/torch calls are method-gated; recognized returned devices are tracked for cleanup | Camera hook, interceptor and lease registry |
| Location | Request/register/get/GNSS/NMEA families are gated; recognized listener registrations are removed on revoke | Location hook and revocation test |
| Microphone | Bounded AudioManager Binder record/capture/input methods are gated | Audio capture hook and denial test |
| Hook readiness | Effective grant fails closed when the matching proxy is unavailable | `CapabilityProxyReadiness` |
| AppOps attribution | Known integer ops and nested Attribution/proxy calls use virtual identity and policy | invocation handler and identity test |
| Audit | Per-generation bounded call/cleanup evidence is exposed in runtime status | Guest capability audit log |
| Broker structure | Permission request/report orchestration moved behind a testable coordinator and gateway | coordinator and self-test |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T9 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Permission/AppOps consistency | Explicit host-backed effective grant plus virtual AppOps clamp | Mature engines generally cover more Android method variants and permission groups | Consistency is strong; breadth remains behind |
| Camera mediation | Bounded reflective Binder methods with fail-closed readiness | VA/NBB-class engines contain broader API/version-specific camera service hooks | Material source gap reduced, device parity unproven |
| Location mediation | Bounded calls plus listener cleanup on policy revoke | Mature engines cover more listener transports, PendingIntent, geofence and GNSS variants | Still behind |
| Microphone mediation | AudioManager Binder subset only | Mature engines may combine Java/Binder/native interception | Major gap remains because AudioRecord/MediaRecorder native paths are open |
| Attribution/AppOps | Nested identity chain, tags and selected integer op codes | Mature implementations usually maintain larger op-code/version tables and attribution variants | Improved but narrower |
| Revocation | Recognized callback/device leases are actively cleaned | Mature engines have deeper service-owned resource tracking | Useful bounded behavior, not complete revocation |
| Runtime architecture | Permission orchestration extracted from 1,700-line Broker | Mature projects commonly split virtual system services into dedicated managers | First step only; Broker remains oversized |
| Device evidence | None by current scope | VA/NBB have more accumulated device adaptation, though branch quality varies | Cannot compare reliability |
| Code governance | Typed policies, explicit boundaries, deterministic host tests | Upstream forks vary | Controlled Sandbox remains strong in evidence discipline |

## Test result

- Capability proxy and Broker split gate: PASS.
- Static Android-source compilation and new self-tests: PASS.
- Complete host verification gate: PASS on the feature branch.
- Emulator and physical-device tests: deferred by current scope.

## Current completion evidence

The evidence matrix tracks 64 capabilities:

- Source: 61 complete, 3 partial, 0 missing; weighted **97.7%**.
- Production wiring: 57 wired, 5 partial, 1 blocked, 1 not applicable; weighted **94.4%**.
- Device evidence: 0 verified, 62 not tested, 1 blocked, 1 not applicable; weighted **0.0%**.

These percentages measure repository-defined evidence coverage. They do not measure APK launch rate, application compatibility or parity with VA/NBB.

## Remaining gap and next priority

VA/NBB remain ahead in Android-version-specific service interception, complete microphone/native capture mediation, PendingIntent location delivery, attribution-chain variants, system-service breadth, 32-bit execution and accumulated application/device compatibility evidence.

The next source priority should continue decomposing `RuntimeBrokerService` and cover Notification/Job/Alarm/Clipboard/Account or Native audio paths without weakening current fail-closed capability rules.
