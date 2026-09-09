# P1-12 reference decision — scheduling callbacks and PendingIntent

Date: 2026-09-09

## Observed failure boundary

`out/verification/realapp-compat-01-capability-final-20260907/capability-matrix.json`
records `CAP-SCHEDULING-NOTIFICATION-ALARM-JOB-FGS` as `FAIL`: notification and its
two PendingIntent callbacks were observed, while `C2_T05_ALARM_PASS`,
`C2_T05_JOB_CALLBACK_PASS`, `C2_T05_FGS_STOP_PASS`, the loop marker and campaign marker
were absent.  The preserved raw log shows the notification sequence completed and then the
alarm was accepted at the API boundary (`C2_T05_ALARM_RETURN`), with the platform reporting
`AlarmManager: canAfford ... false`; it does not contain an alarm callback.  Therefore the
historical evidence proves neither a generic PendingIntent failure nor a Job/FGS implementation
failure.  It is an alarm-delivery/campaign-short-circuit failure on that run and must remain a
FAIL receipt.

## Reference-method comparison

| Area | NBB / VA method examined | CAS corresponding method | Decision |
| --- | --- | --- | --- |
| Started/bound/foreground Service | NBB `ActiveServices.startService`, `bindService`, `unbindService`, `onServiceDestroy`; VA `MethodProxies.StartService.call` and `BindService.call` resolve the virtual component before dispatch | `GuestActivityThreadServiceBridge.start`, `stop`, `route`, `HostConnection`; `RuntimeServiceCoordinator` owns authoritative state | Do not add a manual Guest-Service lifecycle.  Verify the Android Service callback and the stop acknowledgement through the existing ActivityThread bridge. |
| Job schedule and callback | NBB `IJobServiceProxy.Schedule` rewrites a `JobInfo` through its job manager before host scheduling; it is a schedule boundary, not callback proof | `GuestJobServiceBridge.start`, `stop`, `GuestJobCallback.finish`; trusted `VirtualJobService` / Package Service execution capability | Treat `JobScheduler.schedule()` and `getPendingJob()` only as admission/readback.  Require `FixtureJobService.onStartJob` evidence and test cancel/`onStopJob` separately. |
| Notification / system-held PendingIntent | NBB `INotificationManagerProxy` substitutes Guest package identity and namespaces enqueue/cancel/channel methods; VA service proxies select virtual resolution before host delegation | `VirtualPendingIntentRegistry.issue`, `sendPersistent`, `refreshDurable`, `cancel`; system-holder relay and notification interceptor | Preserve generation, creator UID, package revision, `FLAG_UPDATE_CURRENT`, `FLAG_CANCEL_CURRENT` and cancel fencing.  Verify click/delete delivery and stale-sender rejection instead of trusting notification return values. |
| Process/component routing | VA `VActivityManagerService.startProcessIfNeedLocked` reuses a live client only after liveness check and `attachClient`; NBB creates a stub Service request after virtual resolution | CAS broker/session route and `GuestActivityThreadServiceBridge` carry package, user, process slot and generation into the host stub intent | A delayed system callback must be revalidated against the current runtime generation.  No direct host component substitution is permitted. |

The VA README entry is treated only as an external compatibility signal; it is not implementation
or acceptance evidence.

## P1-12 implementation and verification rule

The existing fixture is already designed to make actual callbacks observable:

* `C2T05EventReceiver` persists notification/alarm receiver evidence;
* `FixtureJobService.onStartJob` writes a callback file then calls `jobFinished`;
* `FixtureService.onStartCommand` records foreground promotion and `onDestroy` records stop.

Before changing framework code, run this focused fixture on API 35 and API 36 with short
callbacks.  Classify each missing marker as (1) scheduling admission failure, (2) host callback
not delivered, (3) Guest bridge rejected/revoked it, or (4) fixture collection gap.  Only a
repeatable category (2) or (3), linked to a method above, authorizes an implementation patch.
The historical campaign does not authorize a guessed rewrite of AlarmManager, JobScheduler or
PendingIntent handling.
