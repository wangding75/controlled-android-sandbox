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

`PackageManagementService` in the dedicated `:sandbox_package` process owns the production `SandboxPackageLifecycle`. `SandboxCatalogState` validates package/instance/policy referential integrity and `SandboxCatalogRepository` persists the aggregate in one recoverable schema-v5 catalog. APK and extracted native payloads are addressed by SHA-256 under `files/packages/<package>/revisions/<digest>/`; legacy mutable payloads are copied forward before the first catalog commit. Catalog paths reject outside-root locations and managed symbolic-link traversal.

Product callers use a typed, death-linked `IPackageManagementSession` capability that is bound to the verified main-process PID and application UID. Package metadata, component metadata, permission decisions and bounded AppOps modes are issued as a revision-bound `VirtualPackageStateSnapshot` for each package and virtual user.

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

Receiver implementation ownership is delegated to `RuntimeReceiverCoordinator`, which owns dynamic registrations, Manifest routing, ordered-broadcast tokens and Receiver lifecycle cleanup. Runtime permission request/report orchestration is delegated to `RuntimePermissionCoordinator`, which depends on a narrow session view and `RuntimePermissionGateway`. UI code sends requests but does not mutate runtime state directly; the central Broker remains large but no longer contains Receiver dispatch policy.

Production Binder clients share `RebindableServiceConnector`. A binding Attempt starts a monotonic timeout when `bindService` begins. If Android returns `true` but delivers no callback, the connector records `BIND_TIMEOUT`, clears the current Attempt, releases waiters, safely unbinds once and applies bounded exponential backoff. A late `onServiceConnected` callback is rejected by the Attempt epoch/current-owner recheck and its adapted capability is closed. Timeout, close and callback publication serialize through one connector lock.

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

These are development implementations, not yet proof of complete Android compatibility. Provider Batch, ContentObserver, Session-bound URI Grant, explicit/implicit manifest Receiver routing and a bounded `BroadcastReceiver.PendingResult` completion bridge are locally wired. Generation-bound PendingIntent senders now route Activity, Service and Broadcast delivery through the Broker; Activity-result senders, protected/background broadcasts and full task semantics remain open.

## Framework adapters

`sandbox-framework` installs process-local PackageManager and selected system-service proxies. Package/application/component identity, virtual UID, per-user permission decisions and bounded AppOps modes originate from the package-service snapshot. Direct host-package PackageManager queries are hidden. Hook status and failures are reported; teardown restores original objects.

Activity/task, notification, job and storage adapters remain bounded source implementations. PendingIntent sender identity is generation-bound and known virtual senders route back through the Broker. Clipboard, basic Account, Alarm metadata/callback ownership and Notification/Job ID namespaces now use a Package-Service-owned typed Binder authority scoped to package, virtual user, virtual process and Runtime generation. Clipboard and Account are shared across Guest processes of one virtual user; Alarm delivery is process/generation-owned and reclaimable by the same virtual process after recovery. Full Android Alarm, Account, Notification and Job semantics remain incomplete. Permission and AppOps proxies cover explicit check-style surfaces, known integer operation codes and nested Attribution chains. Camera, Location and bounded AudioManager capture methods are gated through reversible service-field proxies. Effective grants fail closed when the corresponding proxy is unavailable; recognized Location callbacks and Camera handles are released on policy revoke. Native AudioRecord/MediaRecorder and unrecognized Android/OEM service variants remain outside this source boundary.

## Native boundary

`sandbox-native` provides Guest-module-scoped PLT/GOT replacement for selected imported libc/loader/network/audio symbols, plus:

- canonical mapping of Guest private/external paths into the instance root;
- traversal and malformed-path rejection;
- exact/suffix hostname rules;
- IPv4 CIDR allow/deny rules with deny precedence;
- JNI configuration/query APIs.

Imported-symbol rebinding is a best-effort compatibility and redirection layer. Direct `syscall(SYS_*)`, inline assembly and a custom loader can bypass it. The package authority therefore denies packaged ELF/native payloads by default and only admits a Native Guest when an install session records `EXPLICITLY_TRUSTED`. That trust decision and the `BEST_EFFORT_COMPATIBILITY` execution label are persisted and rechecked by RuntimeClient, Runtime Broker, Guest specification parsing and Package Service before Guest startup. Legacy Native records fail closed. This policy does not turn same-UID execution into a hostile-code security boundary; arbitrary untrusted Native code requires a different UID/isolated execution design with Broker-only Host file/network access.

Hook-mediated socket receives use a dedicated network adapter. Message-oriented sources are preflighted with `MSG_PEEK` under a bounded per-socket lock shared by duplicated descriptors; stream peers are checked before receive. Actual payload, address and ancillary data land in bounded temporary storage and are copied into Guest buffers only after policy approval. Accepted descriptors receive independent lock ownership while `dup` aliases share the original ownership. Rejected datagrams remain queued; a rejected `accept/accept4` connection must be accepted and closed because Linux has no pre-accept peer-policy primitive. These rules do not constrain direct syscalls or concurrent custom loaders.

## Diagnostics

Every host/runtime/Guest process writes bounded JSONL evidence under `files/runtime-diagnostics`, with one-file rotation, runtime events, uncaught exceptions and main-thread stall suspicion. Device scripts also collect Logcat, process lists, Activity/Service dumps and memory information.

## Non-goals

- Concealing malicious behavior.
- Bypassing authentication, integrity checks, anti-cheat, financial controls or enterprise policy.
- Reading another application's credentials or private data without authorization.
- Claiming compatibility without versioned, reproducible device evidence.
## Package management authority

Package and virtual-instance mutations are owned by `PackageManagementService` in the dedicated `:sandbox_package` process. Product callers bind through `PackageServiceClient` and receive a death-linked `IPackageManagementSession` capability only after the Android process registry identifies the caller as the host main process. The capability is bound to the caller PID and UID and is revalidated on each call. Guest and runtime processes may reach the non-exported same-UID service Binder, but cannot mint or reuse a management session.

This boundary serializes package writes and reduces privileged API exposure. It does not remove the shared Linux UID between host and Guest processes and is not a hostile-code security boundary.

Package Service also owns a separate Runtime-Broker-only `IVirtualSystemServiceSession` capability. The capability is fixed to package, virtual user, virtual process and Runtime generation and exposes only bounded Clipboard, Account, Alarm and Notification/Job namespace operations. Guest code receives the scoped session Binder through its Runtime specification, never the Package Service root Binder.

## M4-T12 lifecycle ownership

Notification, Notification Channel/Group and Job ownership are persisted by the scoped Package Service
`VirtualSystemServiceStore`. Framework proxies reserve state before host delegation and commit only
after success. Notification and Job `cancelAll` enumerate owned host identifiers and never invoke a
host-global cancel-all operation.

Android JobScheduler callbacks terminate at the non-exported `VirtualJobService` in the trusted
`:sandbox_server` process. Package Service forwards the callback only to the matching package/user/
process/generation capability. The observer returns an explicit acknowledgement; without a complete
Guest `JobParameters` bridge the host requests rescheduling.

Provider cursor/file cleanup delivery is owned by `RuntimeProviderResourceCoordinator`, not by
`RuntimeBrokerService`. The central Broker retains orchestration and Session ownership while Provider
resource invalidation and best-effort physical close delivery are delegated.

## M4-T13 Guest Job execution ownership

Android `JobParameters` and its host callback never cross directly into Guest code. The trusted `VirtualJobService` extracts a bounded `VirtualJobParametersSnapshot` and calls Package Service. Package Service validates the persisted Job owner and current Runtime observer, transitions the record through `DISPATCHING` and `RUNNING`, and creates an `IVirtualJobExecution` capability bound to Guest ID, process, generation and dispatch token.

The Guest runtime reconstructs version-adapted `JobParameters`, invokes the declared Guest `JobService` on its main thread and exposes only a restricted raw `IJobCallback` implementation for `jobFinished`. Completion returns through the scoped execution Binder to the trusted Host callback. Runtime death, callback death, replacement, timeout and stop invalidate the capability and apply an explicit reschedule decision.

## M4-T14 Service lifecycle ownership

`RuntimeServiceCoordinator` owns Broker-side started, bound, foreground and recovery state. `RuntimeBrokerService` supplies only the generic component route and Guest Binder invocation. Bound clients may attach a Binder token; the coordinator links it to death, performs best-effort Guest unbind and removes authoritative connection ownership.

Guest process death clears connection and foreground state. `START_NOT_STICKY` records are destroyed, while sticky and redeliver records enter `RECOVERING`. The new Guest generation must successfully recreate every recoverable Service before ownership moves to that generation. Redelivery carries only the bounded latest action, not an unrestricted host Intent object.

## Binder death-registration linearization

Binder-owned registries use a two-phase registration boundary:

```text
reserve authoritative record
  → linkToDeath
  → recheck same record + Binder liveness
  → publish success
```

`DeathRegistrationHelper` owns the link state and single unlink transition. The registry owner
must insert its reservation before linking and must remove it on any failed recheck. This ordering
is used by Provider observers, active virtual Job executions, virtual-system-service sessions and
ordered-Receiver completion leases. If a test Binder invokes its death recipient synchronously
inside `linkToDeath`, the callback can already find and remove the reserved record; the caller then
observes a failed recheck and cannot publish a dead capability. This source-level linearization does
not substitute for Android Binder-driver, process-death or OEM device evidence.

## Binder collection pagination and binary payload transport

The scoped `IVirtualSystemServiceSession` exposes typed page operations for Account, PendingIntent,
Alarm, Notification/Channel, Job, Shortcut, AppWidget and Settings collections. A
`VirtualPageRequest` carries `maxItems`, `maxBytes` and an opaque continuation token. The Host applies
both limits before returning and reserves transaction headroom below the platform Binder ceiling.
All page operations are appended after the original AIDL method sequence, preserving the transaction
IDs of every pre-existing method for mixed-version compatibility.
A single item that cannot fit after binary offload fails explicitly with
`ITEM_EXCEEDS_BINDER_BUDGET`.

Continuation tokens are process-local capabilities protected with HMAC-SHA256. Their authenticated
state binds the collection/query, virtual package/user/process/generation scope, snapshot revision,
offset and monotonic expiration. The Host recalculates a stable field-level revision for every
page request. Mutation, token tampering, cross-collection use and cross-scope use are rejected rather
than producing mixed snapshots.

Binary fields larger than 64 KiB are replaced by an empty field in the typed page item and a
`VirtualPageBlob` descriptor. The descriptor identifies the page item and field and includes byte
length, SHA-256 and a random session-scoped grant token. The client opens the token through
`openPageBlob`, reads a read-only `ParcelFileDescriptor`, verifies length/digest and reconstructs the
typed item. Grants are bounded by count, total bytes and a two-minute lifetime, are consumed after
one successful open, and are destroyed when the scoped session closes. Active grants are never
silently evicted. A page stops at the 64-grant window and resumes after the client consumes its
handles.

Legacy collection methods are compatibility adapters with a 32-item/128-KiB ceiling. They either
return the complete collection or throw `PAGING_REQUIRED`; offloaded binary values are never silently
lost. Account collection pages use `VirtualAccountSummary(name,type)`. Passwords and tokens do not
cross Binder during enumeration and remain behind `getPassword` and `peekAuthToken`.

## Guest process connection replacement

`RuntimeGuestConnectionPool` treats each slot entry as either an in-flight binding or a live Guest
capability. A caller that finds a completed but dead cached Binder removes that exact entry under the
pool lock and installs one replacement `GuestConnection`; other callers observe the replacement as
binding and wait on the same latch. Retirement has single unbind and disconnect-notification ownership.
Late callbacks from an old connection are rejected by slot-entry identity, so they cannot publish or
remove the replacement.

The pool retries only when death is detected before the `GuestCall` is invoked. Once an operation has
been dispatched, a Binder failure is reported and the call is not replayed because the remote side may
already have committed effects. `DEAD_BINDER`, `DISCONNECTED`, `BIND_REJECTED` and `BIND_TIMEOUT` are
separate recovery diagnostics. These are source/Host-stub state-machine guarantees, not Android Binder
or device validation.
