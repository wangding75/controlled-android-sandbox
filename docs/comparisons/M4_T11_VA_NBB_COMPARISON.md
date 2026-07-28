# M4-T11 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-28

## Iteration scope

M4-T11 replaces generation-local Clipboard, Account, Alarm and Notification/Job namespace state with
a scoped Binder-owned authority in Package Service. The comparison is based on repository source and
host-side tests only. It makes no device-compatibility claim for Controlled Sandbox, VirtualApp or
NewBlackbox.

## New capability in this iteration

| Area | Controlled Sandbox M4-T11 result | Evidence |
|---|---|---|
| Binder boundary | Runtime-Broker-only package/user/process/generation capability | typed AIDL, caller verification and capability lifecycle source gate |
| Clipboard | Package/user persistent content shared across Guest processes | store self-test and remote authority wiring |
| Account | Package/user persistent basic Account/password/token store | store self-test and Account hook path |
| Alarm | Persistent metadata, process/generation owner, retry and recovery claim | owner-filtered callback test and durable store |
| Notification | Persistent ID/channel/group namespace and object-field rewriting | interceptor self-test and namespace store |
| JobScheduler | Persistent Guest-to-host ID namespace across Guest restarts | namespace store and query mapping tests |
| Runtime structure | System-service capability lifecycle extracted into a coordinator | Broker delegation and line-count gate |
| Cleanup/bounds | Instance deletion removes associated state best-effort; payload and collection limits fail closed | package lifecycle integration and store self-test |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T11 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Virtual service authority | Dedicated Binder-owned, scoped and typed authority | Mature engines generally use central virtual managers/services | Architectural gap materially reduced |
| Clipboard | Cross-process package/user state with host fallback denied | Mature engines have broader API/version adapters | Core model closer; device breadth behind |
| Account | Basic durable account/password/token semantics | Mature engines cover authenticators, sessions and async callbacks | Still substantially behind |
| Alarm | Durable metadata and callback recovery for a virtual process | Mature engines proxy host alarms and Android power policy | Persistence improved; platform semantics far behind |
| Notification | Persistent IDs and bounded channel/group field mapping | Mature engines cover channels, callbacks, cancellation and recovery broadly | Still behind |
| JobScheduler | Persistent ID namespace only | Mature engines cover constraints, JobService callbacks and persistence | Large functional gap remains |
| Capability security | Root service hidden; scoped death-linked Binder handed to Guest | Mature engines vary, often with virtual manager capabilities | Clearer boundary, but shared UID remains |
| Device evidence | None by current scope | Mature projects have more accumulated device use | Reliability cannot be compared |
| Engineering governance | Deterministic artifacts, strict source/production/device evidence split | Upstream forks vary | Controlled Sandbox remains strong |

## Current completion evidence

The repository evidence matrix tracks 77 bounded capabilities:

- Source: 74 complete, 3 partial, 0 missing; weighted **98.1%**.
- Production wiring: 70 wired, 5 partial, 1 blocked, 1 not applicable; weighted **95.4%**.
- Device evidence: 0 verified, 75 not tested, 1 blocked, 1 not applicable; weighted **0.0%**.

These percentages measure repository-defined evidence coverage. They do not measure APK launch rate,
application compatibility or parity with VA/NBB.

## Remaining gap and next priority

VA/NBB remain ahead in Android-version adapters, real host Alarm/Job integration, Notification
channels and callbacks, Account authenticators, broader system-service coverage, Native hooks,
32-bit execution and accumulated device compatibility evidence.

The next source priority should complete Notification/Job owned-resource lifecycle and continue
extracting Provider or Service coordination from `RuntimeBrokerService`, while retaining the scoped
Binder authority and fail-closed host fallback rules.
