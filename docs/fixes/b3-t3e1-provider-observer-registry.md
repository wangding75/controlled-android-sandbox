# B3-T3E-1 Provider Observer Registry

## Scope

This stage makes virtual ContentObserver registration and notification broker-owned without relying on an Emulator or physical device.

## Production behavior

- `IProviderObserver` is a versioned Binder callback owned by `sandbox-contract`.
- Registration is authorized through the existing Provider Authority route and requires read access through owner, exported Provider or URI Grant policy.
- Every registration is bound to caller instance, virtual user, caller Session/generation, target Provider instance, target Session/generation, Authority and normalized `content://` URI.
- Duplicate registration IDs are idempotent only when metadata and callback Binder are identical.
- Exact URI and optional descendant matching are isolated by virtual user and Provider generation.
- Self notifications are suppressed unless explicitly enabled.
- Callback Binder death, caller death, target Provider death, instance stop and generation replacement remove registrations.
- Observer callbacks are invoked outside Broker registry locks. Failed callbacks are removed.
- The Broker retains at most 256 active observer registrations.

## Operations

- `PROVIDER_OBSERVER_REGISTER`
- `PROVIDER_OBSERVER_UNREGISTER`
- `PROVIDER_NOTIFY_CHANGE`

## Local evidence

- `ProviderObserverRegistry` domain self-test covers idempotence, descendant matching, virtual-user isolation, owner validation and Session cleanup.
- `BrokerObserverRuntimeSelfTest` covers Binder callback delivery, self-notification policy, conflicting callback rejection, lifecycle cleanup and 16-thread idempotent registration.
- `RuntimeBrokerService` owns registration, notification and cleanup paths.

## Deferred device evidence

Android `ContentResolver.registerContentObserver`, platform `IContentObserver`, OEM Binder behavior and real Provider notifications remain `not-tested` under the current user-approved device-test deferral.
