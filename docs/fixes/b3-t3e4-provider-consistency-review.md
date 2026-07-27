# B3-T3E-4 Provider consistency review

This stage consolidates Provider authority, URI Grant, Cursor, FileDescriptor and ContentObserver lifecycle handling without relying on an Emulator or physical device.

## Implemented

- Added `ProviderLifecycleCoordinator` as the only cross-registry cleanup authority for Broker-owned Provider resources.
- Session disconnect, recovery, recovery failure, explicit stop, virtual-instance stop, Broker destruction and TTL purge now use the same lifecycle entry points.
- Recoverable disconnects preserve Provider authority ownership while revoking every capability issued to the dead Session/generation.
- Terminal disconnects, failed recovery and explicit stop remove Provider authority ownership as well as Observer, URI Grant, Cursor and File resources.
- Successful recovery rebinds Authority records to the new Session/generation before stale capabilities are revoked.
- Cursor and File cleanup returns immutable Lease snapshots so `RuntimeBrokerService` can close the Guest-side physical resource when the target process is still available.
- Runtime status uses one Provider resource snapshot for Authority, Observer, URI Grant, Cursor and File counts.
- URI Grant expiry now exposes an explicit removed-count API and participates in the same TTL purge pass as Cursor and File leases.
- Direct cross-registry cleanup calls were removed from `RuntimeBrokerService`; individual registries remain independently synchronized but lifecycle transitions are serialized by the coordinator.
- Historical R3 documentation now records that Cursor authority was later moved out of `BrokerStateStore` into `BrokerCursorRuntime`.

## Tests

`ProviderLifecycleCoordinatorSelfTest` covers:

- Recoverable disconnect preserving Authority while revoking Observer, Grant, Cursor and File resources.
- Terminal disconnect removing all Provider resources.
- Successful generation recovery and stale-generation denial.
- Failed/explicit Session cleanup.
- Virtual-instance cleanup.
- Unified TTL cleanup.
- Sixteen-thread repeated cleanup with exactly-once total removal.
- Twenty-four-thread mixed disconnect, stop, instance invalidation and expiry with no resource duplication or leaks.
- Broker FileDescriptor closure on every terminal lifecycle path.

Full repository verification includes the new self-test and all existing Activity, Service, Receiver, Provider, Framework and Native gates.

## Deferred device evidence

Android `ContentResolver`, platform `IContentProvider`/`IContentObserver`, Binder FileDescriptor ownership, process death on a real system, OEM behavior and third-party Provider compatibility remain `not-tested` under the user-approved device-test deferral.
