# M5-T6 Development Report — Dedicated Isolated Service Process

## Status

- Source status: PASS
- Production status: PARTIAL
- Android APK build: BLOCKED by the current toolchain environment
- Device evidence: 0
- Base commit: `5a39ff249f52826d5dee7c878eaa362181e1fa64`

M5-T6 replaces the previous ordinary-route fail-closed placeholder with a dedicated Service-only isolated transport. It does not claim that Android assigned an isolated UID in a built APK or that Guest code successfully executed under SELinux.

## Delivered

### Sixteen dedicated workers

The runtime manifest declares sixteen non-exported `android:isolatedProcess="true"` Services. Each immutable worker class owns one logical isolated slot. These workers are separate from the ordinary Guest process pool and are sized by the shared `ProcessSlotContract`.

### Independent Session and Service state

The Broker owns a second `SessionRegistry` with capacity sixteen and a dedicated `RuntimeServiceCoordinator`. Package/user stop, stale revision purge, Binder death and Broker shutdown clean both ordinary and isolated resources without sharing slots.

### Typed capability protocol

`IIsolatedGuestProcess`, `IsolatedProcessRequest` and `IsolatedProcessResult` bind:

- protocol;
- Session and generation;
- process slot;
- virtual user;
- package, process, component and revision;
- Service operation;
- opaque capability token;
- platform PID and UID evidence.

The legacy component payload remains bounded inside the typed request. The worker rejects outer/payload identity mismatches and never exposes the ordinary Runtime Broker Binder to Guest code.

### Service-only policy

Only isolated Services are routed. Isolated Activity, Receiver and Provider requests fail closed. An isolated component cannot fall back into an ordinary Guest process.

### Recovery and diagnostics

Binder disconnect/death marks the isolated Session for recovery, disconnects Service state and requires a new generation. Typed runtime status aggregates ordinary and isolated capacity; the legacy status path also exposes isolated capacity, use and Session count.

## Validation

Passed source-side checks cover:

- typed contract validation and defensive payload copies;
- deterministic route matching and class normalization;
- non-Service and wrong-operation rejection;
- ordinary Guest fail-closed behavior;
- independent sixteen-slot capacity and saturation;
- isolated process-death recovery and generation advancement;
- static Android-source compilation and existing regressions.

## Remaining risk

Production remains `partial` because the current environment cannot establish whether a real isolated UID can access the staged Guest APK/data layout, load Guest native libraries under SELinux, and survive Android AMS restart/rebind behavior. These are Android build and Emulator/device questions rather than claims satisfied by Host tests.
