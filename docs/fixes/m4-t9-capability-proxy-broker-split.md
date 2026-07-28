# M4-T9 capability proxies and Runtime permission coordinator

Date: 2026-07-28

## Scope

M4-T9 adds bounded method-level mediation for Camera, Location and microphone-related AudioManager Binder calls, live revocation cleanup, per-Guest-generation capability audit, Attribution/Proxy AppOps handling and the first extraction of permission orchestration from `RuntimeBrokerService`.

This is a source/host-tested iteration. It does not claim complete Android service interception or device compatibility.

## Implemented

### Dynamic capability decision

`CapabilityAccessPolicy` combines the current generation's effective virtual permission state and AppOps mode:

- Camera: `CAMERA` plus `android:camera`.
- Microphone: `RECORD_AUDIO` plus `android:record_audio`.
- Location: fine or coarse permission plus the corresponding AppOp.

A permission grant is insufficient when AppOps is `IGNORED` or `ERRORED`.

### Reversible framework hooks

`FrameworkHooks` now attempts dedicated reflective hooks for:

- Camera manager Binder fields;
- Location manager Binder fields;
- AudioManager Binder fields used by the bounded capture surface.

The hooks are installed from the trusted host service Context rather than through `GuestContext`. This prevents an initially denied capability from making hook installation impossible and later exposing an unproxied manager after permission grant.

If a capability becomes effectively granted while its method proxy is unavailable, `CapabilityProxyReadiness` rejects Guest preparation or live policy refresh with `CAPABILITY_PROXY_UNAVAILABLE`.

### Method-level gates

The `core`-owned `CapabilityServiceInterceptor` classifies protected calls before delegation. Capability policy/audit/lease primitives remain in the dependency-leaf `capability` package, avoiding a package cycle with `identity`:

- Camera connect/open/torch/device operations;
- Location request/register/get/geofence/GNSS/NMEA operations;
- AudioManager record/capture/input operations.

Denied calls do not reach the host delegate. Cleanup-style calls remain available so a Guest can release resources after revocation.

### Live revocation cleanup

`CapabilityLeaseRegistry` tracks recognized callback/device resources:

- Location listener registrations are paired with a compatible remove/unregister method.
- Returned Camera device interfaces/objects are paired with `close`, `disconnect` or `release` when available.
- Recognized Audio recording callback/input registrations use compatible unregister/stop/release methods.

Policy refresh and Guest shutdown perform best-effort cleanup. Failures are retained in the capability audit rather than silently ignored.

### Capability audit

Each Guest generation owns a bounded 128-event `GuestCapabilityAuditLog`. Runtime status exposes:

- total retained events;
- denied/failed count;
- active tracked leases;
- compact event records.

The log is process-local diagnostic evidence. It is not durable security logging.

### Attribution and Proxy AppOps

The AppOps proxy now:

- maps known integer codes for coarse/fine location, Camera and record-audio;
- handles proxy-operation method families;
- recognizes nested Attribution identity chains;
- rewrites and restores package/UID through the complete bounded chain;
- preserves `attributionTag` values;
- maps unknown integer operations to a virtual unknown operation that resolves to `MODE_DEFAULT` without host delegation.

### Runtime Broker extraction

Runtime permission request/report validation and Package Service delegation moved to `RuntimePermissionCoordinator` behind:

- `RuntimePermissionGateway`;
- a narrow session resolver;
- an immutable `PermissionSession` view.

`RuntimeBrokerService` no longer directly owns permission validation and Package Service result construction. Other component and provider responsibilities remain in the large Broker service.

## Verification

Host-side verification covers:

- denied Camera and microphone calls avoid host delegation;
- Location callback lease cleanup after permission revocation, including signatures where request and executor objects precede the listener;
- Camera device cleanup after AppOps revocation;
- bounded capability audit behavior with per-generation monotonic sequence ownership;
- effective grant failure when the corresponding hook is absent;
- nested Attribution chain and nested Attribution-state holder rewriting/restoration with tag preservation;
- known and unknown integer AppOps handling;
- Runtime permission coordinator session validation and gateway delegation;
- static Android-source compilation of all production and test-harness Java.

## Explicit limitations

- Native `AudioRecord`, `MediaRecorder`, `AudioSystem` and arbitrary JNI capture paths are not intercepted by the AudioManager Binder hook.
- Camera/Location Binder field names and signatures vary by Android/OEM and remain device-gated.
- Only recognized callback/device shapes can be released automatically.
- Already transferred file descriptors, native handles or unrecognized service objects cannot be forcibly reclaimed by this source model.
- Shared application UID remains a fundamental limit; this is not a hostile-APK security boundary.
