# M4-T14 comparison with VirtualApp and NewBlackbox

## Scope

This report compares only the M4-T14 Service lifecycle increment. Source presence, production wiring and device evidence remain separate. VA/NBB repository claims are treated as implementation references, not independent compatibility proof.

## Added in this iteration

- Dedicated Runtime Service coordinator.
- Started, bound and foreground ownership in one registry.
- Latest-start-ID stop semantics.
- Binder-death cleanup for bound clients.
- Sticky and redeliver Guest process recovery.
- Recovery failure cleanup and stale-generation rejection.
- App-side bound Service lease helper.

## Comparison

| Capability | Controlled Sandbox M4-T14 | VirtualApp | NewBlackbox | Current gap |
|---|---|---|---|---|
| Started Service ownership | Explicit source model and production route | Mature virtual AMS model | Broad modern implementation | Device behavior unverified |
| Bound Service multi-client state | Connection IDs and reference ownership | Mature IServiceConnection routing | Mature Binder routing | Complete Android callback adapter missing |
| Client Binder death | Death-linked lease and best-effort Guest unbind | Mature process/Binder cleanup | Mature process/Binder cleanup | No device ordering evidence |
| Sticky restart | Recreated in next Guest generation | Mature restart handling | Broader runtime handling | Broker restart persistence absent |
| Redeliver intent | Latest action retained and redelivered | Mature Intent lifecycle | Broad Intent lifecycle | Current model carries bounded action only |
| stopSelfResult semantics | Latest start ID protects newer work | Mature Android parity | Broad Android parity | Device parity unverified |
| Foreground Service | State and routing model | Broader notification/AMS integration | Broader modern Android handling | Notification deadline/type enforcement absent |
| Broker decomposition | Dedicated typed coordinator ownership | Mature service managers | Project-specific | Central Broker remains large |
| Device evidence | 0% by current policy | Long implementation history, branch dependent | Version/project dependent | Controlled Sandbox has no Android evidence |

## Evidence-based judgment

M4-T14 closes important source-level lifecycle gaps. Service ownership is now explicit, death-linked and generation-aware, and a recovering Guest process can recreate sticky/redeliver Services without retaining dead connections.

VA and NBB remain substantially ahead in Android-version-specific ActivityManager/Binder adaptation, foreground service restrictions, complete `IServiceConnection` behavior, persistent process recovery and accumulated real-App compatibility. M4-T14 cannot be considered equivalent until device builds and lifecycle tests pass across target API levels.

## Metrics after M4-T14

- Capability entries: 90.
- Source: 86 complete, 4 partial, weighted 97.8%.
- Production: 82 wired, 6 partial, 1 blocked, 1 not applicable, weighted 95.5%.
- Device: 0 verified; weighted 0.0%.

These are repository evidence metrics, not APK compatibility rates.

## Unfinished items

1. Complete Android `IServiceConnection` callback adapter.
2. Runtime Broker process-death persistence.
3. Foreground notification and service-type enforcement.
4. Background-start restrictions and Android-version adapters.
5. Isolated/external Service policy.
6. Device lifecycle and OEM validation.

## Next priority

M4-T15 should strengthen Activity/Task virtualization: launch modes, Intent flags, result routing, task restoration and recent/running task queries.
