# M5-T6 Development Plan — Dedicated Isolated Service Process

## Baseline

- Source baseline: `5a39ff249f52826d5dee7c878eaa362181e1fa64`.
- Reference snapshots: `ref/upstream/VirtualApp` and `ref/upstream/NewBlackbox` remain read-only and excluded from product builds.
- Current execution environment: Java 21, no locked JDK 17, Android SDK/NDK or Emulator.

## Frozen scope

### Dedicated isolated Service transport

- Support only manifest components whose type is `SERVICE` and whose parsed declaration has `isolatedProcess=true`.
- Reject isolated Activity, Receiver and Provider requests before ordinary Guest process allocation.
- Do not reinterpret an ordinary VA/NBB-style Stub process as an Android isolated UID.

### Independent process ownership

- Declare sixteen Host Services using `android:isolatedProcess="true"`.
- Allocate them from a separate sixteen isolated slots `SessionRegistry`; ordinary Guest capacity is governed by the shared 64-slot contract.
- Bind one package, virtual user, declared process, package revision and component to one active isolated lease.
- Keep Session ID, generation and process slot independent from the ordinary Guest registry.

### Typed and capability-scoped Binder protocol

- Carry protocol, Session, generation, slot, user, package, process, component, revision, operation and capability token as typed top-level fields.
- Permit a bounded legacy `Bundle` only for the existing component operation payload.
- Require top-level and payload identities to match before Guest code runs.
- Remove the ordinary Runtime Broker Binder from the isolated Guest payload.

### Lifecycle and recovery

- Validate platform PID and UID returned by the worker and reject a worker that runs under the Host application UID.
- Track Service state in a dedicated coordinator.
- On Binder death, disconnect the isolated worker, move the Session to recovery and advance generation before reuse.
- Stop and clean isolated sessions together with package stop, purge and Broker shutdown.
- Publish combined runtime metrics plus an explicit isolated-slot breakdown.

## Validation

- Typed contract validation and defensive-copy tests.
- Service-only route and non-Service rejection tests.
- Ordinary fail-closed versus dedicated-transport Guest policy tests.
- Independent ordinary and sixteen-slot isolated registry tests.
- Slot saturation, Binder-death state and generation recovery tests.
- Static Android compilation, all historical gates, Host and Native regression, evidence matrix and reproducible source package.
- Attempt the real Android build entry without claiming an APK when the locked toolchain is absent.

## Device boundary

The source stage cannot prove:

- real isolated UID assignment and SELinux domain behavior;
- access to staged Guest APK, native libraries and data roots;
- Android AMS binding, restart and process-reclamation behavior;
- API/OEM compatibility.

## Execution result

**Execution status: SOURCE PASS / PRODUCTION PARTIAL / DEVICE BLOCKED**
