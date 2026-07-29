# M5-T3 Comparison with VirtualApp and NewBlackbox

## Scope

This comparison is limited to source-visible Ordered Broadcast, PendingResult and Foreground Service behavior. No device compatibility claim is made for this repository, VirtualApp or NewBlackbox based solely on README statements.

| Capability | Controlled Sandbox M5-T3 | VirtualApp / NewBlackbox reference position | Remaining gap |
|---|---|---|---|
| Ordered result propagation | Source-wired result code/data/extras and abort state | Mature projects generally mediate ordered Receiver delivery through broader AMS compatibility layers | Android API/OEM execution evidence |
| Chain timeout accounting | Explicit global deadline, timed-out/skipped counters and terminal reason | Existing mature sandboxes benefit from years of platform compatibility fixes | Platform timeout and background-delivery behavior |
| PendingResult finish bridge | One-shot Broker authority, Guest PendingResult, Binder-death and payload validation | VA/NBB-style systems typically depend on hidden framework/Binder compatibility | Real hidden API constructor and finishReceiver matrix |
| Foreground start/promotion | Pending state, bounded promotion deadline, background allow/exemption policy | Mature products generally include platform-specific Service hooks | Actual AMS deadline and restriction enforcement |
| FGS type validation | Requested mask must be within declared source mask | Platform implementations vary by Android version | Manifest-to-runtime type integration and API-level validation |
| Notification ownership | ID/tag/type bound to foreground state | Mature products integrate with NotificationManager/SystemUI adapters | Real notification permission, channel and SystemUI behavior |
| Process death recovery | Sticky/redeliver Service restarts in pending promotion state | Mature products have more real-device recovery history | Process kill, OEM and boot/restart evidence |

## Judgment

M5-T3 closes the three previously explicit source-wiring gaps in the repository capability matrix:

- `receiver.ordered-source-model`
- `receiver.ordered-pending-result-bridge`
- `runtime.foreground-service-state-model`

The evidence is stronger than the earlier partial model because deadlines, Binder death, type checks and notification ownership are executable Host-tested policies. VA and NewBlackbox still have the stronger practical position where Android-version, OEM and third-party application compatibility matters, because this project has not yet produced device evidence.

## Device evidence

Device evidence remains 0. Real Android build, Emulator execution, SystemUI behavior, background-start restrictions and OEM differences are unresolved.
