# M4-T12 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

Comparison date: 2026-07-28

## Iteration scope

M4-T12 adds persistent owned Notification/Channel records, durable Job spec/state, safe owned-only
`cancelAll`, a trusted host Job callback bridge with explicit Guest acknowledgement, and a further
Provider cleanup extraction from `RuntimeBrokerService`. Evidence remains source/host-side only.

## New capability and evidence

| Area | Controlled Sandbox M4-T12 result | Evidence |
|---|---|---|
| Notification ownership | Persistent ID/tag/channel/payload state with reserve/commit/remove | store recreation and Framework rollback tests |
| Notification `cancelAll` | Enumerates and cancels only the virtual scope's host IDs | Framework self-test; host-global `cancelAll` is never invoked |
| Notification channels | Persistent typed channel/group payloads; host-only query results filtered | Parcelable, store recreation and wrapper-list isolation tests |
| Job state | Persistent Guest JobInfo, host ID, owner process/generation and state | Package Service recreation test |
| Host Job bridge | Non-exported `VirtualJobService` in trusted Runtime process | Manifest, typed root Binder dispatch and source gate |
| Callback safety | Job runs only after Guest acknowledgement; reconnect replaces stale observer and Guest cannot self-claim delivery | synchronous observer, reconnect and unacknowledged-state tests |
| Broker structure | Provider resource cleanup and close delivery extracted | coordinator self-test and 1,351-line Broker gate |

## Relative position

| Dimension | Controlled Sandbox after M4-T12 | VA/NBB relative position | Assessment |
|---|---|---|---|
| Notification ID ownership | Persistent and scoped with safe cancelAll | Mature engines have broad version-specific Notification adapters | Core ownership gap reduced |
| Channel/group lifecycle | Typed persistent metadata and bounded object rewrite | Mature engines cover more APIs, ranking and listener behavior | Still behind |
| Job persistence | Guest spec and ownership survive Package Service restart | Mature engines integrate more complete JobScheduler semantics | State model closer |
| JobService execution | Trusted host callback route, but Guest JobParameters bridge intentionally unacknowledged | Mature engines generally proxy actual JobService callbacks | Major gap remains |
| Provider cleanup architecture | Dedicated coordinator outside central Broker | Mature engines vary; often use dedicated virtual managers | Maintainability improved |
| Android/OEM evidence | None | VA/NBB have substantially more accumulated device use | Cannot compare reliability |
| Build/evidence governance | Deterministic artifacts and source/production/device split | Upstream forks vary | Controlled Sandbox remains strong |

## Evidence matrix

The repository now tracks 82 bounded capabilities:

- Source: 78 complete, 4 partial, 0 missing; weighted **97.6%**.
- Production: 74 wired, 6 partial, 1 blocked, 1 not applicable; weighted **95.1%**.
- Device: 0 verified, 80 not tested, 1 blocked, 1 not applicable; weighted **0.0%**.

The lower source percentage compared with M4-T11 is intentional: the newly exposed Guest
`JobParameters` execution bridge is recorded as partial rather than hidden inside an ID-namespace
item. These values do not measure APK compatibility or parity with VA/NBB.

## Remaining VA/NBB gap

VA/NBB remain ahead in actual JobService parameter/callback virtualization, Job constraints and
recovery, Notification service API-version adapters, listener/ranking behavior, broader system
services, Native hooks, 32-bit execution and device compatibility evidence.

The next source priority should complete the Guest JobParameters bridge or move to Activity/Service
coordination extraction without overstating the current callback path.
