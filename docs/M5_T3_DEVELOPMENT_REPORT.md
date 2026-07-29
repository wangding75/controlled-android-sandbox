# M5-T3 Development Report — Ordered Broadcast, PendingResult and Foreground Service

## Status

- Source status: PASS
- Production source wiring: PASS
- Android APK build: BLOCKED by the current toolchain environment
- Device evidence: 0
- Base commit: `b4b94ec8a5eb0ab2c4c72f8f723932d2c28507db`

## Delivered source capabilities

### Ordered Broadcast

- Preserves deterministic manifest priority and result code/data/extras propagation.
- Enforces a bounded chain-wide budget: default 60 seconds, maximum 120 seconds.
- Records delivered, failed, skipped and timed-out Receiver counts.
- Distinguishes receiver abort, policy abort, chain timeout and delivery failure terminal reasons.
- Keeps failure and abort policy outside `RuntimeBrokerService`.

### PendingResult bridge

- Tracks Broker completion Binder death from the Guest process.
- Cancels the local finish token when the completion Binder dies.
- Rejects replay, late completion, stale ownership and invalid result payloads.
- Bounds result data, keys and string values using the ordered-broadcast payload limits.
- Prevents custom finish tokens from being forwarded to Host AMS.

### Foreground Service

- Models `startForegroundService` as a pending promotion.
- Uses a 5-second default and 10-second maximum source deadline.
- Rejects disallowed background starts unless a bounded exemption reason is supplied.
- Validates requested foreground-service type bits against the declared type mask.
- Requires a valid notification ID and records notification tag/type ownership.
- Supports promotion, demotion, stop, process death, sticky/redelivery recovery and re-promotion.
- Expires unpromoted Services and performs best-effort Guest stop cleanup.
- Keeps started/bound Service behavior and M4-T14 lifecycle semantics intact.

## Source architecture

The source authority remains split by responsibility:

- `ForegroundServiceStateMachine` owns Android-independent promotion policy.
- `ServiceRuntimeRegistry` owns started/bound/foreground lifecycle state.
- `BrokerServiceRuntime` applies successful Guest operations to Broker authority.
- `RuntimeServiceCoordinator` owns connection death, process recovery and deadline cleanup.
- `ManifestBroadcastDispatcher` owns ordered chain policy.
- `OrderedReceiverPendingResultBridge` owns Guest PendingResult construction and completion forwarding.

`RuntimeBrokerService` remains a coordinator and did not absorb the new state machines.

## Verification

New and updated self-tests cover:

- foreground pending state and promotion;
- notification and service-type ownership;
- exact deadline expiration;
- background-start denial;
- sticky/redeliver process recovery and re-promotion;
- ordered-chain timeout, skipped count and terminal reason;
- policy abort versus Receiver abort;
- PendingResult completion Binder death;
- invalid ordered result extras;
- replay and late completion behavior.

All pre-existing M4-T14 through M5-T2 source/Host regression gates remain required by `scripts/verify-all.sh`.

## Evidence boundary

This report proves source structure, static Android compilation and Host-test behavior. It does not prove:

- Android ActivityManager foreground-service deadline enforcement;
- SystemUI notification behavior;
- Android 12–16 background-start policy variants;
- hidden PendingResult constructor compatibility on devices;
- OEM ordered-broadcast scheduling;
- real APK execution.

The current execution environment still has JDK 21 instead of the locked JDK 17 and lacks the Android SDK/NDK, so real APK build remains separately blocked.
