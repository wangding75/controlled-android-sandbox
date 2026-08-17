# T57-R03-P2A Fault / Death / Recovery

RESULT: PASS with classified remainder

Maturity: `RD_BASELINE_P2A`
VA Pro: `NOT_PROVEN`

## Root cause

Death recovery already existed as a lazy generation state machine
(`markSlotDisconnected` → `RECOVERING` → next `prepareGuest` /
`beginRecovery` → `RuntimeComponentRecoveryCoordinator.recover`).
Cleanup lists were duplicated and ordinary Binder death did not close
the generation-scoped SystemService capability.

This is not a list of crash-type patches. The missing piece was a
single ownership catalog that says, for every runtime object, whether
death sweeps it, revokes it, or preserves it.

## Target architecture

`RuntimeOwnershipGraph` is the policy catalog:

| Kind | Durability | Death |
| --- | --- | --- |
| session / slot / generation | ephemeral | fence / sweep |
| Activity / Service / Receiver | ephemeral | sweep / later rebind |
| Provider lease / observer / cursor | ephemeral | sweep |
| Provider persistable grant | durable | preserve, rebind |
| Broker `IIntentSender` | durable | preserve |
| SystemService durable state | durable | preserve |
| SystemService callback | ephemeral | sweep |
| Native / isolated peer lease | ephemeral | revoke |
| Job / Alarm / Notification records | durable | preserve |

`RuntimeOwnershipSweep` applies that plan through existing coordinators.
There is no `clearAll()`. Durable PendingIntent / Job / Alarm /
Notification records are not dropped.

Ordinary `handleGuestDisconnect` and isolated `handleDisconnect` /
`stopSession` now share the same plan. Ordinary death now closes the
generation-scoped SystemService callback, which previously leaked until
the next recover/stop.

## Static

`RuntimeOwnershipGraphSelfTest` PASS via
`python tools/static_android_compile.py`.

## RD (`RD测试`, serial resolved dynamically)

Evidence: `artifacts/capability-audit/p2a/20260817T115104Z`

| Probe | Induced | Recover prepare |
| --- | --- | --- |
| Java uncaught (`CAS_FAULT_JAVA_UNCAUGHT`) | yes, `LAUNCH_GATE_FAILED` | PASS, new session |
| Main-thread crash | yes | PASS |
| Service crash | start ack then fixture throw | PASS |
| Native SIGSEGV | `LAUNCH_GATE_FAILED` before window confirm | PASS |
| Native abort | same | PASS |
| Isolated native | isolated slot 11 / uid `99001` started | PASS |
| Activity 25s stall | launch completed after stall | PASS |
| Service 25s stall | start completed after stall | PASS |
| Provider 25s stall | `CURSOR_READY` after stall | PASS |
| `am force-stop` guest | hold + force-stop | PASS (`KILL_RECOVERED`) |

Reboot: executed earlier (`20260817T113358Z`) with a new `boot_id`.
First recover failed because the harness omitted `trustNativeGuest`
(`UNTRUSTED_NATIVE_GUEST_DENIED`, TEST_INFRA). After the extra was
added, later prepares on the post-reboot device succeeded. The last
campaign skipped a second reboot to keep ADB stable.

## Classified remainder

1. Isolated worker still cannot `dlopen` `libcontrolled_sandbox_fixture.so`
   (`InMemoryDexClassLoader` nativeLibraryDirectories). Isolated SIGSEGV
   therefore used process death after the JNI failure, not a proven
   `ISOLATED_HOSTILE` fatal-signal path. General isolated native-lib
   projection, not a package-specific hack.
2. ANR fixtures were not shortened. The 25s stalls completed and
   returned; system ANR-kill evidence is PARTIAL.
3. `HostileCapabilityRegistry.revokeSession` remains debug-campaign
   owned. Production native capability is generation-fenced on the next
   prepare.
4. Dynamic receivers, cursors, observers and ServiceConnections stay
   ephemeral and are not resurrected.

## Forbidden paths not taken

- No `clearAll()`
- Durable Broker `IIntentSender` not dropped
- Persistable Provider grants still preserved on `RECOVERING` disconnect
- ANR fixtures still sleep 25s
- Crash fixtures still throw / abort / SIGSEGV
