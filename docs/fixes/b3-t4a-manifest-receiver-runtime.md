# B3-T4A Manifest Receiver authority and on-demand process activation

Baseline: `main@1747adf1bfbfb5cb6c57c8a7fb7e831dbba39b3c`

## Scope

- Parse manifest Receiver class, process, enabled, exported, permission and action metadata in the Broker.
- Index Receiver declarations by virtual user and package instance.
- Resolve explicit component broadcasts through the Broker-owned manifest index.
- Enforce same-virtual-user delivery, exported boundaries and Receiver permission requirements.
- Reuse a READY/ACTIVE target process generation when available.
- Generate a deterministic Guest preparation request for the Receiver's declared process when it is not running.
- Bind and remove manifest Receiver process generations during prepare, recovery, disconnect and stop.
- Keep dynamic Receiver delivery on its existing broker registry path.

## Compatibility rules

- An explicit component does not need to match an intent-filter action.
- A cross-package caller cannot target a non-exported Receiver.
- A permission-protected Receiver accepts a cross-package caller only when the caller manifest requests the permission in the current source model.
- A Receiver permission inherits `application android:permission` when the component omits its own permission.
- For legacy manifests that omit `android:exported`, a declared intent-filter supplies the historical exported default. Explicit `android:exported` always wins.
- Cross-virtual-user broadcast delivery is fail-closed.
- Packages must have been indexed by a prior Broker prepare before they can be activated by another package.

## On-demand activation

The activation key is deterministic:

`u<virtualUserId>:<packageName>#<processName>`

The Broker preserves the indexed APK/native/application template, overwrites the target package, virtual user, process and Receiver class, then executes the normal `prepareGuest` transaction. Concurrent activation attempts serialize through the existing session allocator; later attempts reuse the prepared generation.

## Verification

- Manifest parser permission, application-permission inheritance and legacy exported-default tests.
- Explicit Receiver resolution and deterministic activation-request tests.
- Cross-user, non-exported, missing-permission and wrong-component rejection.
- Session/generation binding removal and recovery rebinding.
- Sixty-four concurrent resolutions produce the same activation key.
- PackageManager metadata exposes the parsed Receiver permission.
- Full repository verification includes `BrokerManifestReceiverRuntimeSelfTest`.

## Remaining boundary

This stage does not claim Android broadcast compatibility. Ordered broadcasts, implicit manifest resolution, platform permission grants, background execution limits, protected system broadcasts, framework-originated broadcasts and real process startup remain device-dependent or later-source work.
