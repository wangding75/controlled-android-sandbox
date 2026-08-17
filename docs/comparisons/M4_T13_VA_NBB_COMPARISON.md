# M4-T13 comparison with VirtualApp and NewBlackbox

## Scope

This report compares only the M4-T13 Guest JobService execution increment. It separates source presence, production wiring and device evidence. No README claim from any project is treated as independent compatibility proof.

## Added in this iteration

- Typed, bounded JobParameters snapshot.
- Trusted Host JobService to Package Service dispatch.
- Package/user/process/generation-bound execution state machine.
- Guest JobService creation and main-thread `onStartJob`/`onStopJob` calls.
- Scoped one-shot Guest `jobFinished` capability.
- Host callback death, Runtime replacement and timeout rescheduling.
- Removal of direct Guest `finishJob(id)` authority.
- Bounded `JobWorkItem` dequeue/complete transport with Broker-owned projected work IDs.
- API32 RD evidence for ordered two-item drain and Host `jobFinished` receipt.

## Comparison

| Capability | Controlled Sandbox M4-T13 | VirtualApp | NewBlackbox | Current gap |
|---|---|---|---|---|
| Job ID namespace | Persistent virtual/host mapping | Mature virtualization model | Broad virtualization model | Device behavior unverified here |
| Host JobService bridge | Implemented in source and production path | Established framework coverage | Established framework coverage | Android-version adapter evidence absent |
| Guest JobService start/stop | Implemented through Guest runtime | Mature implementation lineage | Broader modern-version implementation | No device validation |
| Guest jobFinished | One-shot scoped Binder capability with Host receipt | Supported through virtual service stack | Supported through virtual service stack | Stop-reason/version matrix remains open |
| JobWorkItem dequeue/complete | Bounded cross-process transport; API32 two-item device evidence | Mature framework coverage | Mature framework coverage | API33–36/OEM behavior not verified |
| Process/generation ownership | Explicit package/user/process/generation binding | Mature process model | Mature process model | Current model has clearer typed ownership, less field coverage |
| Persistent state recovery | DISPATCHING/RUNNING recover to SCHEDULED | Broader runtime recovery | Broader runtime recovery | Reboot/OEM behavior unverified |
| Job constraints | Host JobInfo retained | Broader system integration | Broader system integration | Constraint/result compatibility incomplete |
| Device evidence | API32 RD-11 real two-item transport and Host receipt | Long usage history, branch/version dependent | Project/version dependent | API33–36/OEM behavior remains unverified |

## Evidence-based judgment

M4-T13 closes the largest M4-T12 Job lifecycle gap at source level: a scheduled Job can now reach the declared Guest `JobService` and return completion through a constrained capability. The state and IPC design are explicit and testable.

VA and NBB remain ahead in Android-version adaptation, real Binder behavior, process recovery and accumulated application compatibility. M4-T13 cannot be considered equivalent until real Android builds and JobScheduler tests pass across target API levels.

## Metrics after M4-T13

- Capability entries: 86.
- Source: 83 complete, 3 partial, weighted 98.3%.
- Production: 79 wired, 5 partial, 1 blocked, 1 not applicable, weighted 95.9%.
- Device: 0 verified; weighted 0.0%.

These are repository evidence metrics, not APK compatibility rates.

## Unfinished items

1. Real Android JobParameters constructor and hidden callback validation.
2. Full stop-reason/version matrix.
3. Constraint and expedited Job behavior across Android versions.
4. OEM JobScheduler adaptation.
5. Device reboot and persisted Job recovery.

## Next priority

M4-T14 should extract Service lifecycle ownership from `RuntimeBrokerService` and complete started/bound/foreground Service coordination while retaining the M4-T13 Job bridge.
