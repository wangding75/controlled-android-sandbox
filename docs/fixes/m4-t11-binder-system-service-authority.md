# M4-T11 — Binder-owned virtual system-service authority

Date: 2026-07-28

## Scope

M4-T11 moves the bounded Clipboard, Account, Alarm and Notification/Job namespace state introduced in
M4-T10 out of each Guest generation and into `PackageManagementService` in the dedicated
`:sandbox_package` process. Runtime Broker receives a death-linked capability scoped to package,
virtual user, virtual process name and Runtime generation, then passes only that capability to the
Guest process.

This iteration is source/host-tested only. It does not claim Android Binder/OEM compatibility or full
AlarmManager, AccountManager, NotificationManager or JobScheduler parity.

## Binder capability boundary

`IPackageService.openVirtualSystemServiceSession` may only be called by the verified Runtime Broker
process. The returned `IVirtualSystemServiceSession` is bound to:

- host application UID;
- client Binder lifetime;
- Guest package name;
- virtual user ID;
- virtual process name;
- Runtime generation.

The Guest receives the scoped session Binder in `GuestPackageSpec`; it never receives the Package
Service root Binder and cannot select another package, user or process through the capability.

## Durable authority

`VirtualSystemServiceStore` persists bounded state in `sandbox-system-services.json` using temp-file,
fsync and atomic-replace semantics:

- marshalled virtual Clipboard content;
- basic Account names, types, passwords and auth tokens;
- Alarm metadata and marshalled Parcelable delivery tokens;
- stable Notification and Job guest-to-host ID mappings.

Clipboard and Account state is shared between Guest processes of the same package and virtual user,
while remaining isolated from another virtual user and the host services. The authority fails closed
on oversized Binder payloads and enforces bounded Account, token, Alarm and namespace counts to avoid
unbounded Guest-driven memory or disk growth. Mutations restore their prior in-memory state when the
atomic persistence step fails.

## Alarm ownership and recovery

Each persisted Alarm is owned by virtual process name and Runtime generation. Delivery is sent only
to the matching active capability observer, preventing duplicate delivery to another Guest process.
When the same virtual process starts under a newer generation, listing its alarms atomically claims
them for the new generation and re-registers delivery callbacks.

If no matching process is connected at the trigger time, the authority retains the Alarm and retries
later. One-shot alarms are removed only after delivery; repeating alarms advance deterministically.
This is not Android AlarmManager wakeup, Doze or reboot scheduling.

## Framework integration

`VirtualSystemServiceState` now accepts a `VirtualSystemServiceAuthority`. Production Guest startup
constructs `RemoteVirtualSystemServiceAuthority`, which marshals supported Parcelable values across
the typed Binder session. Standalone framework self-tests retain an in-process authority-free mode.

Notification channel/group objects receive bounded ID/group rewriting around delegated calls, with
field restoration afterwards. Notification and Job `cancelAll` remain fail-closed until resource
ownership can be enumerated safely.

## Lifecycle cleanup

Runtime Broker owns `RuntimeSystemServiceCoordinator`, which creates and closes one scoped capability
per Runtime session generation. Guest preparation fails closed if the Binder capability is absent or
invalid.

After a virtual instance is deleted, Package Service performs best-effort cleanup of its Clipboard,
Account, Alarm and namespace state. A cleanup persistence failure is exposed through the combined
maintenance warning without falsely reverting the already committed package Catalog transaction.

## Verification

Host-side verification covers:

- typed Binder contracts without `Bundle` business payloads;
- Account and Alarm Parcelable round trips;
- shared Clipboard callbacks within one package/user scope;
- Clipboard and Account virtual-user isolation;
- stable persistent Notification/Job namespace mappings;
- process/generation-owned Alarm delivery without duplicate cross-process callbacks;
- one-shot Alarm removal;
- instance-state cleanup;
- Runtime capability injection and death-linked close paths;
- static Android-source compilation and the complete existing architecture, Java, native-host and
  deterministic-source gates.

## Explicit limitations

- Actual Android service binding, Binder death and process-restart behavior remain device-gated.
- Persisted Alarm delivery requires a Parcelable token that can be reconstructed by the Guest class
  loader.
- AlarmManager wakeup, Doze, exact-alarm permission and reboot semantics are not implemented.
- Clipboard listener and ClipData behavior across Android/OEM versions remains unverified.
- Account Authenticator, OAuth UI and the complete asynchronous AccountManager protocol are absent.
- Notification/Job full lifecycle, callbacks, constraints and `cancelAll` remain partial.
- Shared application UID remains a fundamental security limitation.
