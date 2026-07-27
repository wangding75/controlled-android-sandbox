# Architecture

## Layering

```text
fixture-basic (independent test APK)

app (product/UI/import/instance registry)
  ↓
sandbox-runtime (session broker and Guest component runtime)
  ├─ sandbox-contract (versioned AIDL)
  ├─ sandbox-domain (dependency-free policy/state/parser)
  ├─ sandbox-framework (Android framework identity adapters)
  └─ sandbox-native (C++ path/network policy JNI boundary)
```

`scripts/check-architecture.py` rejects reverse dependencies, target-App package special cases and upstream-project identifiers in production source.

## Trust boundaries

Imported APKs are untrusted input. Import performs:

1. streaming size limit and ZIP signature check;
2. bounded binary-manifest parsing;
3. PackageManager package-name cross-check;
4. signing-certificate continuity and version-code downgrade policy;
5. transaction-directory staging and immutable SHA-256 revision publication;
6. one atomic package/virtual-instance catalog switch with failed-switch rollback;
7. bounded ABI-specific native-library extraction with duplicate/path checks.

Only canonical paths under the host application's private `files` directory may enter the runtime broker.

## Package lifecycle authority

`SandboxPackageLifecycle` is the product-side authority for import, clone, instance status and deletion. `SandboxCatalogState` validates package/instance referential integrity and `SandboxCatalogRepository` persists the aggregate in one recoverable catalog. APK and extracted native payloads are addressed by SHA-256 under `files/packages/<package>/revisions/<digest>/`; legacy mutable payloads are copied forward before the first catalog commit. Catalog paths reject outside-root locations and managed symbolic-link traversal.

The current authority is serialized in-process. A later iteration must move package lifecycle ownership behind a Binder service before independent installer or management processes are introduced.

## Runtime authority

`RuntimeBrokerService` is the sole owner of:

- process-slot leases;
- virtual UID assignment;
- session ID and generation;
- retained Guest Binder connection;
- Binder-death recovery state;
- prepared Guest specification;
- one-time, expiring Activity route tokens;
- Provider Authority, Cursor, FileDescriptor, ContentObserver and URI Grant ownership, coordinated through one lifecycle cleanup authority.

UI code sends requests but does not mutate runtime state directly.

## IPC contracts

Binder contracts are migrating incrementally from string-keyed `Bundle` payloads to versioned Parcelable models. The first typed path is App → Broker runtime status:

```text
RuntimeStatusRequest
  → IRuntimeBroker.runtimeStatusV2
  → RuntimeStatusResult
     ├─ RuntimeStatusSnapshot
     └─ SandboxError
```

The legacy `Bundle runtimeStatus()` method remains temporarily available and delegates to the typed implementation through `RuntimeStatusLegacyAdapter`; it does not own duplicate business logic. New callers must use the typed method. Other Binder methods remain Bundle based until later A1 stages migrate them individually.

Runtime Status business handling is also isolated from the Binder service:

```text
IRuntimeBroker.Stub → CallerGuard → RuntimeStatusDispatcher → RuntimeStatusSource
```

The dispatcher depends on `Clock` and `AuditSink` ports, while `BrokerRuntimeStatusSource` is the concrete adapter that reads Broker registries. `RuntimeBrokerService` does not build the typed status snapshot.

## Domain ports

Interfaces are introduced only at replaceable or side-effecting boundaries:

- `Clock` for monotonic runtime time;
- `TokenGenerator` for opaque identities;
- `AuditSink` for bounded audit output;
- `SessionMetricsRepository` for read-only diagnostics.

`SessionRegistry` remains the concrete session state authority but implements the narrow metrics repository and receives its token generator. Other registries remain concrete until a real replacement boundary exists.

## Guest process bootstrap

```text
Package + virtual user request
  → session allocation
  → retained bind to :guestN
  → DexClassLoader and APK Resources
  → GuestContext with instance data directories
  → native policy configuration
  → PackageManager identity hook
  → Guest Application.attachBaseContext/onCreate
  → component runtime
```

One process slot hosts one session generation at a time. Binder death moves a READY/ACTIVE session to RECOVERING; the next prepare advances its generation and invalidates routes from the dead generation.

## Data isolation

Each virtual user receives a distinct root:

```text
files/instances/u{virtualUserId}/{packageName}/
  data/
  files/
  cache/
  code_cache/
  databases/
  shared_prefs/
  webview/
```

Virtual UIDs preserve a stable app ID across virtual users while changing the user portion. Package APKs are shared read-only; mutable data is instance-scoped.

## Component bridges

Current bridges are intentionally explicit:

- Activity: a Stub Activity owns the system token/window; a Guest Activity receives mirrored framework fields and forwarded lifecycle callbacks.
- Service: direct Guest instance lifecycle for started services.
- Receiver: Broker-indexed explicit and action-indexed implicit manifest Receiver routing with deterministic process activation, plus session-owned dynamic Receiver delivery and a bounded ordered-result source model.
- Provider: direct `attachInfo`/`onCreate`, broker-routed CRUD/file operations, Cursor/FileDescriptor leases and broker-owned observer callbacks.

These are development implementations, not yet proof of complete Android compatibility. Provider Batch, ContentObserver, Session-bound URI Grant and explicit/implicit manifest Receiver routing are locally wired. Ordered result policy exists at source level, while platform `BroadcastReceiver.PendingResult`, protected/background broadcasts, PendingIntent and full task semantics remain open.

## Framework adapters

`sandbox-framework` currently installs a process-local PackageManager proxy and synthesizes package/application/UID/permission identity for the active Guest. Hook status and failures are reported; teardown restores the original object.

Activity/task, AppOps, notification, job and storage service adapters are not yet complete and are not represented as passed capabilities.

## Native boundary

`sandbox-native` provides:

- canonical mapping of Guest private/external paths into the instance root;
- traversal and malformed-path rejection;
- exact/suffix hostname rules;
- IPv4 CIDR allow/deny rules with deny precedence;
- JNI configuration/query APIs.

It does not yet patch arbitrary Guest ELF imports or intercept every libc call. That distinction is enforced in the milestone documentation.

## Diagnostics

Every host/runtime/Guest process writes bounded JSONL evidence under `files/runtime-diagnostics`, with one-file rotation, runtime events, uncaught exceptions and main-thread stall suspicion. Device scripts also collect Logcat, process lists, Activity/Service dumps and memory information.

## Non-goals

- Concealing malicious behavior.
- Bypassing authentication, integrity checks, anti-cheat, financial controls or enterprise policy.
- Reading another application's credentials or private data without authorization.
- Claiming compatibility without versioned, reproducible device evidence.
