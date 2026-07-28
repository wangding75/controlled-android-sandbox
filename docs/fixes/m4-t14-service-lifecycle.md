# M4-T14 Service coordinator and lifecycle hardening

## Status

SOURCE/HOST PASS. Emulator, physical-device and third-party APK validation remain intentionally deferred.

## Objective

Move Service lifecycle ownership behind a dedicated Broker coordinator and complete the source-level semantics that were still missing after the original started/bound registry wiring: client Binder-death cleanup, latest-start-id stop behavior, foreground ownership, and sticky/redeliver process recovery.

## Production flow

```text
RuntimeClient
→ RuntimeBrokerService generic component route
→ GuestComponentRuntime Service callback
→ RuntimeServiceCoordinator
→ ServiceRuntimeRegistry
```

`RuntimeBrokerService` no longer owns `BrokerServiceRuntime` directly. It delegates successful operations, disconnect, recovery, stop and Broker destruction to `RuntimeServiceCoordinator`.

## Started Service lifecycle

The registry now tracks:

- latest delivered start ID;
- delivered start count;
- latest action used for redelivery;
- `START_NOT_STICKY`, `START_STICKY` and `START_REDELIVER_INTENT`;
- foreground ownership;
- Runtime generation.

`STOP_SERVICE_START_ID` mirrors bounded `stopSelfResult` semantics: a stale start ID cannot stop a newer start delivery. An explicit stop clears started and foreground ownership while preserving a still-bound Service.

## Bound Service lifecycle

Each binding retains a stable connection ID. `RuntimeClient.BoundServiceLease` supplies a Binder client token. The coordinator links the token to death and, on client death:

1. removes the Broker connection lease;
2. sends a best-effort Guest `UNBIND_SERVICE` call;
3. removes the connection from the authoritative Service registry;
4. destroys the Service only when neither started nor bound ownership remains.

Explicit unbind removes and unlinks the same lease. Legacy callers without a Binder token remain supported but are reported as not death-tracked.

## Guest process recovery

When a Guest process dies:

- all bound connections are cleared;
- foreground ownership is cleared;
- `START_NOT_STICKY` Services are destroyed;
- sticky and redeliver Services enter `RECOVERING`.

After a new Guest generation is prepared, the coordinator recreates each recoverable Service. Sticky recovery uses an empty action. Redelivery recovery supplies the latest recorded action and marks the request as redelivered. Only after every Guest restart succeeds does the registry move ownership to the new generation.

Recovery failure invalidates old and new Service state, cleans related component resources and marks the new Runtime Session failed instead of leaving it indefinitely preparing.

## Foreground state

`START_FOREGROUND_SERVICE` and explicit foreground promotion/demotion are routed and reflected in Broker/Guest state. Stopping started ownership clears foreground state.

This is a lifecycle state model only. Android foreground-notification deadlines, service-type declarations, background-start restrictions and OEM enforcement remain device-gated and are recorded as partial capability evidence.

## Host-side evidence

- Domain tests for stale start ID, foreground state and redelivery metadata.
- Broker registry tests for multi-client ownership, virtual-user isolation and recovery.
- Coordinator tests for Binder death, best-effort Guest unbind and generation recovery.
- Static Android source compilation with local API stubs.
- Existing package, permission, component, Framework and Native tests remain passing.

## Remaining limitations

- Service ownership is Broker-memory state and is not persisted across Runtime Broker process death.
- Actual Android `startForeground` notification/type enforcement is not implemented.
- `onTaskRemoved`, isolated services and external services are not modeled.
- Service connection callbacks are represented by component request/result Bundles rather than a complete Android `IServiceConnection` adapter.
- Device Binder ordering, rebind behavior and OEM background execution policy remain unverified.
