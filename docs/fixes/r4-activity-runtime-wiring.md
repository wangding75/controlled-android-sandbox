# R4 Activity runtime production wiring

Baseline: `main@8bf6870e215669d23832f631efd27c189fa02afd`

## Scope

- Replace RuntimeBrokerService's legacy RouteTable launch authority with the B2 ActivityTaskLedger, ActivityLaunchCoordinator, and OneTimeRouteStore.
- Bind every launch/consume operation to virtual user, package, process, session, and generation.
- Route ordered Guest Activity lifecycle and saved-state events back to the broker-owned ledger.
- Preserve virtual task records across recoverable Guest process death while revoking stale generation routes.
- Remove the process-local ActivityTaskRegistry from GuestRuntimeEnvironment.

## Verification

- BrokerActivityRuntimeSelfTest exercises one-time consume, replay rejection, lifecycle state, saved state, owner isolation, generation recreation, and invalidation.
- Static Android compilation includes the new AIDL activityEvent method.
- `scripts/verify-all.sh` passed three consecutive runs before merge.

## Remaining device-dependent boundary

The host/Guest Activity attach bridge still mirrors Android private fields. Android API 29-36 emulator validation is required before claiming runtime compatibility. Activity-result completion initiated by a Guest calling finish/setResult also requires a dedicated bridge stage.
