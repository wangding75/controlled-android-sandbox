# T57-R03-P1-00 P0 evidence and ClassLoader closure

RESULT: PASS (static + contract). RD session recorded separately when RD测试 is available.

## Why Service used host DexPathList

`RuntimeClient.startService` sent `START_SERVICE` into `GuestComponentRuntime.getOrCreateService`.
That path loaded the guest class through `session.classLoader` (`GuestClassLoader`), which
falls back to the host parent on a dex miss. The host debug APK does not contain
`NativeAdversarialProbeService`, so the thrown `ClassNotFoundException` cited the host
`DexPathList`.

Activity/Provider/Receiver framework routes already used LoadedApk / process ClassLoader
after `GuestLoadedApkBridge`. Service debug/broker start did not.

API32 `CREATE_SERVICE` has no start Intent extras, so the framework Stub is created first
and the Guest class is installed on `SERVICE_ARGS`/`BIND_SERVICE`. That is still
framework-owned; the defect was the broker START_SERVICE bypass.

## Fix

Unified defining-loader contract: `GuestDefiningLoader`.

- Activity, Service, Receiver, Provider, JobService instantiate through the same contract.
- `GuestClassLoader.loadDefinedClass` searches only the defining dex loader.
- Broker `START_SERVICE`/`STOP_SERVICE` use `GuestActivityThreadServiceBridge` when installed.
- Manual `GuestComponentRuntime` lifecycle remains only for host JVM self-tests (no bridge).

No GuestComponentRuntime rewrite of framework lifecycle. No host ClassLoader injection.
No packageName special case. No CNFE fallback to host for component classes.

## Process slot device evidence

- Production contract unchanged: ordinary=64, isolated=16.
- `SlotPool.reserveExact` + `SessionRegistry.allocateExact` occupy specific slots without
  starting 64 live APK-loaded processes.
- `SLOT_TARGET` / `SLOT_PAD_COUNT` on prepare / isolated start force high-slot transport:
  `GuestProcessService{N}` / `IsolatedGuestProcessService{N}`.
- Self-test proves occupying every slot except 62/63 leaves that slot for the live process,
  and the 65th ordinary / 17th isolated pad is `NO_PROCESS_SLOT`.
- Debug command `slot-campaign` + isolated-service `slotTarget` is the RD path.

## PendingIntent system-holder

- Fixture `SystemHolderPendingIntentActivity` posts a Notification and a short Alarm whose
  PendingIntents are held by the system process.
- `SystemHolderPendingIntentReceiver` records delivery after guest death.
- Debug command `pi-system-holder` + `tools/capability/run_p1_00_rd.py`.

## Tests

- `GuestDefiningLoaderSelfTest`
- `GuestClassLoaderSelfTest` defined-class no host fallback
- `GuestComponentFactorySelfTest`
- `ProcessSlotConcurrencySelfTest` exact high-slot occupancy

## KI-R03-NATIVE-010

Status: FIXED
