# M4-T10 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-28

## Iteration scope

M4-T10 adds bounded PendingIntent identity/routing, basic Alarm/Clipboard/Account virtualization,
Notification and Job namespaces, a system-service coverage matrix and a second structural extraction
from `RuntimeBrokerService`. The comparison is based on repository source and host-side tests. It
contains no device-compatibility claim for Controlled Sandbox, VA or NBB.

## New capability in this iteration

| Area | Controlled Sandbox M4-T10 result | Evidence |
|---|---|---|
| PendingIntent | Guest package/user/generation sender registry with Broker Activity/Service/Broadcast delivery | registry, framework interceptor and Runtime dispatcher tests |
| Alarm | Generation-local scheduler and cancellation without host Alarm namespace fallback | Alarm hook and virtual service self-test |
| Clipboard | Guest-generation content/listener isolation; host clipboard hidden | Clipboard hook and zero-host-call test |
| Account | Basic explicit Account/password/token store; host accounts hidden | Account hook and isolation test |
| Notification | Stable Guest-to-host ID/tag namespace with failed-call rollback | virtual service interceptor test |
| JobScheduler | Stable Job ID namespace and filtered query mapping | virtual service interceptor test |
| Broker structure | Receiver authority extracted into a dedicated coordinator | coordinator test and Broker line-count gate |
| Governance | 15-service matrix records source, isolation, fallback and device evidence | `verification/system-service-coverage-matrix.json` |

## Relative capability position

| Dimension | Controlled Sandbox after M4-T10 | VA/NBB relative position | Assessment |
|---|---|---|---|
| PendingIntent identity | Bounded sender ownership and internal component routing | Mature engines cover more Android versions, sender types and system delivery paths | Core source gap reduced; still behind |
| Alarm | In-process generation-local scheduling | Mature engines proxy host AlarmManager with persistent identity and Android power-policy handling | Large functional gap remains |
| Clipboard | Strong fail-closed host hiding for recognized calls | Mature engines generally provide cross-process virtual clipboard service and version adapters | Isolation behavior is clear; lifecycle breadth behind |
| Account | Basic local Account/password/token model | Mature engines cover Account service Binder variants, authenticators and async callbacks | Substantially behind |
| Notification | ID/tag namespace only; selected channel strings | Mature engines virtualize channels, callbacks, cancellation and process recovery more broadly | Substantially behind |
| JobScheduler | ID/query/cancel namespace for bounded methods | Mature engines handle JobService callback routing, constraints and persistence | Substantially behind |
| Receiver architecture | Dedicated coordinator owns Receiver registries and ordered tokens | Mature engines use dedicated virtual managers/services | Architecture is moving in the correct direction |
| System-service breadth | Explicit 15-service evidence matrix | VA/NBB-class projects cover more services and API-specific variants | Current breadth remains narrower |
| Device evidence | None by current scope | Mature projects have more accumulated device use, although fork quality varies | Reliability cannot be compared |
| Code governance | Fail-closed boundaries, testable coordinators and deterministic artifacts | Upstream forks vary | Controlled Sandbox remains strong in evidence discipline |

## Current completion evidence

The repository evidence matrix tracks 72 bounded capabilities:

- Source: 69 complete, 3 partial, 0 missing; weighted **97.9%**.
- Production wiring: 65 wired, 5 partial, 1 blocked, 1 not applicable; weighted **95.1%**.
- Device evidence: 0 verified, 70 not tested, 1 blocked, 1 not applicable; weighted **0.0%**.

These percentages measure repository-defined evidence coverage. They do not measure APK launch rate,
application compatibility or parity with VA/NBB.

## Remaining gap and next priority

VA/NBB remain ahead in Binder-owned cross-process virtual system services, Android-version adapters,
PendingIntent sender variants, Alarm/Job persistence, Notification channels/callbacks, Account
authenticator flows, Native hooks, 32-bit execution and accumulated device compatibility evidence.

The next source priority should either move generation-local service state behind a Binder-owned
authority or continue decomposing Provider/Service responsibilities from `RuntimeBrokerService`.
The project should not expand README compatibility claims until device evidence exists.
