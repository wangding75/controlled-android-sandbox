# B3-T1 Service runtime production wiring

Baseline: `main@13a8a61b52327e8dcea492bc6b9ac9cd2b76ab0a`

## Scope

- Make the broker-owned `ServiceRuntimeRegistry` the production state authority for started and bound Guest services.
- Record start count, last start id, restart mode, active bindings, state, and generation after successful Guest operations.
- Keep Service records scoped by virtual user, package, component, process, and Guest generation.
- Clear binding ownership on Guest process death.
- Recover only `START_STICKY` and `START_REDELIVER_INTENT` records after successful Guest generation replacement.
- Destroy Service records on explicit Guest stop/invalidation.
- Allow a destroyed Service component to be started again as a fresh record.

## Isolation rule

Process-death and recovery matching uses `instanceId + processName + generation`. Matching by process name alone is forbidden because two virtual users can run the same package and process at the same generation.

## Verification

- Started sticky Service enters `ACTIVE` and records restart mode.
- Bound connection ownership is recorded and removed correctly.
- A stopped but still-bound Service remains active.
- Final unbind destroys an unowned Service.
- A destroyed Service can be started again.
- Recoverable process death rotates Service generation only after Guest preparation succeeds.
- Failed recovery invalidates stale Activity and Service records.
- Two virtual users sharing the same package/process do not affect each other's Service records.
- Full repository verification includes `BrokerServiceRuntimeSelfTest`.

## Remaining device-dependent boundary

The broker state model does not prove Android Service lifecycle compatibility. API 29-36 emulator testing is still required for `onStartCommand`, bind/unbind callbacks, foreground-service restrictions, process death, and actual redelivery behavior.
