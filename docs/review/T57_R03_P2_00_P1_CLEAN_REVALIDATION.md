# T57-R03-P2-00 P1 clean revalidation

RESULT: PASS

Resumed from HEAD `3209b6b52f14dca4f1d5d667f6b02946afceff69` with the paused P2-00 WIP
retained. No reset/restore/clean.

## Isolated peer admission

Root cause was runtime peer identity, not slot capacity. Isolated workers receive a
platform UID (observed `99080` on slot 15) and cannot present the host signature
permission. `RuntimePeerPolicy.requireTrustedBinderCaller` therefore rejected the
first Broker callback with `UNTRUSTED_RUNTIME_PEER_UID`.

Contract implemented in `RuntimeIsolatedPeerRegistry`:

- Broker publishes a pending lease: session + generation + slot + capability token
- Isolated worker registers through `IsolatedPeerAdmissionBinder` before
  `GuestRuntimeEnvironment.prepare`
- Admission binds the Binder caller UID to that lease
- Wrong token / slot / host UID / rebind / unregistered UID fail closed
- No `uid >= 99000` allowlist

Coordinator monitor is no longer held across isolated Binder prepare/invoke, so the
worker can call back into the Broker without deadlocking.

Isolated `ROUTE_FRAMEWORK_SERVICE` is a legal isolated Service operation. The isolated
worker starts the guest Service locally through the defining-loader path; it does not
re-enter Broker `ROUTE_FRAMEWORK_SERVICE`.

### RD API32 (`RD测试`, serial resolved dynamically `127.0.0.1:16416`)

| Slot | Result |
| --- | --- |
| isolated 0 | PASS live start, observed slot 0 |
| isolated 7 | PASS |
| isolated 8 | PASS |
| isolated 14 | PASS |
| isolated 15 | PASS (`isolatedPlatformUid=99080`) |
| 17th allocate | `NO_PROCESS_SLOT` (fail-closed) |

Evidence:

- `artifacts/capability-audit/p2-00/20260817T105941Z/isolated_slots.json`
- `artifacts/capability-audit/p2-00-retry/20260817T111454Z/retry.json`

Static negatives: `RuntimePeerAdmissionSelfTest`.

No `UNTRUSTED_RUNTIME_PEER_UID` on legitimate isolated workers.

## System-holder PendingIntent

P1 failed at `Guest Activity alias pool exhausted ... index=-1` because the fixture
Activity was missing from the imported virtual package state. DebugCommand now
re-imports when the host-installed APK SHA-256 differs from the catalog revision so
the fixture enters production routing.

`ActivityFieldBridge` projected `VirtualComponentSnapshot.metaData()` (a list) onto
`ActivityInfo.metaData` (a Bundle) and crashed the guest process. Metadata is now
converted with `GuestPackageMetadataMapper.toMetadataBundle`.

RD after the fix:

- `pi-system-holder` launch: `LAUNCH_PASS`
- Notification channel `cas.system.holder` held by the system (`notification_held_before_kill=true`)
- Path: virtual package state → production Stub/Alias (`StubActivity54` / slot variants) → guest Activity → NotificationManager
- Post-death delivery file was not observed in guest `data/files` after `am force-stop` of the guest package. Alarm dump after the probe did not retain `SYSTEM_HOLDER`. Recorded as remaining evidence gap, not as system-holder launch failure.

Evidence: `artifacts/capability-audit/p2-00-retry/20260817T111454Z/retry.json`

## Pre-WIP vs post-WIP collect-all

Pre-WIP (HEAD only): PASS=28 KNOWN_ISSUE=14 NEW_REGRESSION=0
(`artifacts/capability-audit/all/20260817T100808Z`)

Post-WIP after the SystemHolder `Handler(getMainLooper()).postDelayed` compile fix
(`artifacts/capability-audit/all/20260817T112100Z`):

- PASS=28
- KNOWN_ISSUE=14
- EXPECTED_WARNING=0
- NEW_REGRESSION=0
- FAIL=14 (all classified KNOWN_ISSUE)
- UNVERIFIED=0
- static-android-compile PASS
- runtime-hardening PASS

An earlier post-WIP collect-all (`20260817T111627Z`) recorded NEW_REGRESSION=2
because the fixture still called `View.postDelayed`, which the static Android stub
does not implement. That compile error also suppressed the runtime-hardening
receipt. Those two gates are PASS on the current tree.

Audit exit code 1 is diagnostic: 14 pre-existing Known Issues remain classified.

## Remaining gaps

1. System-holder post-death Alarm/Notification invoke → Broker restore file evidence
   remains UNVERIFIED after `am force-stop` of the guest package. Launch +
   Notification channel hold is proven; durable post-death delivery is not.
2. Ordinary slot 0 probe still sends an empty processName (`Invalid process name`)
3. Pre-existing M10 / R03 known issues (14 classified FAILs)
4. API33–36 / OEM / ARM / VA Pro still UNVERIFIED / NOT_PROVEN
