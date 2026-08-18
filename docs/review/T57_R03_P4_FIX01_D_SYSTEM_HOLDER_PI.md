# T57-R03-P4-FIX01-D — System-holder PendingIntent after guest death

RESULT: `SYSTEM_HOLDER_ALARM_DELIVERED_AFTER_GUEST_KILL`

Evidence: `artifacts/capability-audit/fix01d/20260818T044548Z`

RD instance `RD测试` API 32 (`127.0.0.1:16416`).

## What was proven

1. Guest PendingIntents are issued as Broker tokens, then rewritten to a
   Host `:sandbox_server` relay so Android AMS owns a real
   `PendingIntentRecord`.
2. NotificationManager posted a host-namespaced notification
   (`CAS system-holder` / `cas.system.holder`).
3. AlarmManager accepted `setExact` as Host uid 10193
   (`*walarm*:com.warden.controlledsandbox.action.RELAY_PENDING_INTENT`).
4. The live guest stub was identified by Host UID + spoofed guest
   process name (`com.warden.controlledsandbox.fixture`, pid 9011), not
   by `am force-stop` of the physical fixture package.
5. `run-as com.warden.controlledsandbox.debug kill -9 9011` killed that
   PID. Broker pid 8901 and Host pid 8830 survived.
6. 20s later AlarmManager fired the system-held sender. Broker logged
   `SYSTEM_HOLDER_RELAY token=pi-7p` and cold-started
   `:guest54` (pid 9232).
7. Recover prepare created a new session
   (`ea262ab3…` → `296ea8a5…`) with a new PID (9011 → 9367). Old session
   is stale. Generation stayed 1; session+PID fencing is the proof.

## Production changes

- `PendingIntentFrameworkInterceptor` rewrites `getIntentSender`
  package + intents to Host `RuntimePendingIntentRelayReceiver` and
  passes through to AMS. A process-local Binder proxy is not enough:
  `ActivityManager.sendIntentSender` only sends a real
  `PendingIntentRecord`.
- Relay receiver lives in `:sandbox_server` and asks
  `RuntimeFrameworkOperationCoordinator.dispatchSystemHeld`.
- PendingIntent alarm `set`/`cancel` is `HOST_DELEGATED_REWRITTEN` so
  AlarmManager holds the sender. Listener alarms stay virtual.
- Notification commit no longer marshalls Binder-bearing Notification
  payloads into the Broker store.
- Debug `pi-system-holder-cancel` cancels the host notification without
  force-stopping Host/Broker.
- Kill uses `run-as HOST kill -9` because shell `kill` is
  `Operation not permitted` on this MuMu image.

## Honest remainder

- Guest `system-holder-delivered.json` was not readable after cold bind
  (`run-as` on the instance path returned empty). Delivery is proven
  from Broker relay + new `:guest54` start, not from the fixture file.
- API 32 `cmd notification cancel` does not exist; cancel is Host
  `NotificationManager.cancel`.
- Notification deleteIntent and Alarm share the same AMS record / relay
  path. This run's timestamped `SYSTEM_HOLDER_RELAY` matches the 20s
  alarm, not a separate notification-cancel line.

No Broker-send substitute was used. AlarmManager held and fired the
sender after the live guest stub was killed.
