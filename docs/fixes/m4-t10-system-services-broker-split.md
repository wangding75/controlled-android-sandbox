# M4-T10 — System-service breadth and Receiver Broker extraction

Date: 2026-07-28

## Scope

M4-T10 adds bounded source-level virtualization for PendingIntent, AlarmManager, ClipboardManager,
AccountManager, NotificationManager and JobScheduler. It also moves Receiver implementation
ownership out of `RuntimeBrokerService` into `RuntimeReceiverCoordinator`.

This iteration is source/host-tested only. It does not claim Android device compatibility or full
parity with Android framework service semantics.

## Implemented

### Virtual PendingIntent identity

`VirtualPendingIntentRegistry` binds each virtual sender to:

- Guest package;
- virtual user;
- Runtime generation;
- sender kind;
- request code and bounded Intent identity;
- one-shot, no-create, cancel-current, update-current and immutable lifecycle flags.

`PendingIntentFrameworkInterceptor` handles known ActivityManager/ActivityTaskManager sender calls.
Known virtual senders expose Guest package and virtual UID rather than the host identity. Delivery is
routed back through the existing Runtime Broker Activity, Service or Broadcast paths. Cross-package
explicit delivery is rejected. Activity-result sender delivery remains unsupported.

### Alarm

Alarm calls are intercepted before the host Alarm service and scheduled inside generation-local
state. Set, repeating set, cancellation and exact-alarm capability query have bounded behavior.
Host clock/time-zone mutation is denied. Elapsed-realtime trigger values are normalized to the local
wall-clock scheduler.

The current scheduler is process/generation local. It is not persistent across Guest process death,
Doze-aware or an implementation of Android exact-alarm policy.

### Clipboard

Clipboard content and listeners are stored in Guest-generation state. Host Clipboard data is never
used as fallback. Read, write, clear and listener notification are covered for recognized signatures.

The state is not yet Binder-persisted or shared between separate Guest processes for the same
virtual user.

### Account

A bounded Account store supports basic list, explicit add/remove, password and auth-token operations.
Unsupported Authenticator, OAuth UI and asynchronous AccountManager signatures fail closed rather
than exposing host accounts.

The store is generation-local and not a full Android AccountManager/AuthToken service.

### Notification and Job namespaces

Notification IDs/tags and Job IDs are mapped into generation-local host namespaces before delegated
calls. Query results are filtered and mapped back to Guest Job IDs. Failed delegate calls roll back
new namespace allocations.

Unknown cancellation does not create a new mapping. Notification and Job `cancelAll` are currently
fail-closed because delegating those calls under the host package could remove unrelated host-owned
resources. Notification channel-object rewriting and durable cleanup after process death remain
incomplete.

### Receiver Broker extraction

`RuntimeReceiverCoordinator` now owns:

- dynamic Receiver registration authority;
- explicit and implicit Manifest Receiver routing;
- ordered-broadcast dispatch state;
- ordered completion Binder tokens;
- Receiver Session/recovery/instance/global cleanup.

`RuntimeBrokerService` delegates Receiver operations and no longer imports concrete Receiver
registries or ordered-token implementation classes. Its size decreased from 1,695 lines at M4-T9 to
1,396 lines in M4-T10.

## Failure-path hardening

- Failed Notification and Job delegate calls remove namespace mappings created for that call.
- Unknown Notification/Job cancellation remains virtual and does not allocate a host ID.
- Unsupported Clipboard, Account and Alarm signatures fail closed.
- Job and Notification cancel-all calls do not reach host-wide namespace operations.
- PendingIntent registries are closed with the Guest generation.
- Receiver lifecycle cleanup remains centralized after the Broker extraction.

## Verification

Host-side verification covers:

- Clipboard isolation and listener dispatch without host delegate access;
- Account isolation and basic credential/token state;
- in-process Alarm delivery and cancellation;
- Notification/Job stable ID mapping and failure rollback;
- PendingIntent stable identity, update, one-shot, immutable and cancellation behavior;
- Guest package/UID PendingIntent metadata;
- Receiver coordinator dynamic delivery and Session cleanup;
- Runtime Broker source ownership and maximum line-count gate;
- static Android-source compilation of production and test-harness Java;
- the complete existing Java, architecture, native-host and reproducible-source gates.

## Explicit limitations

- Clipboard, Account, Alarm and namespace maps are generation-local rather than Binder-owned durable
  state shared across Guest processes.
- PendingIntent Activity-result delivery is not implemented.
- PendingIntent and framework Binder signatures remain Android/OEM device-gated.
- Alarm Doze, wakeup, exact-alarm permission UI, idle policy and reboot persistence are absent.
- Notification channel objects, listener callbacks, durable cleanup and cancel-all are incomplete.
- Job constraints, persistence, service callbacks and cancel-all are incomplete.
- Account authenticator/OAuth flows are not implemented.
- Shared application UID remains a fundamental security limitation.
