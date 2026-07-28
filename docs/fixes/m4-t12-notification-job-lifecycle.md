# M4-T12 — Notification/Job lifecycle and Provider resource coordinator

Date: 2026-07-28

## Scope

M4-T12 replaces the M4-T11 Notification/Job ID-only persistence with bounded owned-resource
lifecycle records in the Binder-owned Package Service. It also adds a trusted host `JobService`
bridge, makes Job callback delivery explicitly acknowledged, implements safe per-Guest `cancelAll`,
and extracts Provider cursor/file cleanup delivery from `RuntimeBrokerService`.

This remains source/host evidence. No Android device, OEM framework or real third-party APK result is
claimed.

## Notification lifecycle

`VirtualSystemServiceStore` schema 2 persists, per package and virtual user:

- Guest and host Notification IDs;
- Guest and host tags;
- channel identity;
- `RESERVED` or `ACTIVE` lifecycle state;
- bounded marshalled Notification payload;
- channel and channel-group metadata.

The Framework interceptor reserves ownership before delegating to the host, commits only after the
host call succeeds, and removes the reservation after successful cancellation. Delegate failure
rolls back a newly created reservation. `cancelAll` never calls host-global `cancelAll`; it enumerates
only the current virtual scope's records and cancels those host IDs individually. Notification and
channel payloads are passed from the production Framework interceptor into the durable authority;
channel query results, including list-wrapper results, are filtered to the current virtual namespace
before they are returned to Guest code.

## Job lifecycle

Job records now persist:

- Guest and host Job IDs;
- marshalled Guest `JobInfo` payload;
- virtual process and Runtime generation owner;
- `RESERVED`, `SCHEDULED` and `RUNNING` states.

The interceptor rewrites the delegated `JobInfo` service to the non-exported trusted
`VirtualJobService` in `:sandbox_server`, while preserving the original Guest JobInfo payload in the
Package Service. Host callbacks are dispatched only to the matching package/user/process/generation
capability.

`IVirtualSystemServiceObserver.onJobReady` returns an acknowledgement. A Job becomes `RUNNING` only
when the Guest callback explicitly returns true. Reconnecting the same scope/process/generation
replaces the stale observer so Clipboard, Alarm and Job callbacks are not delivered twice. The Guest
capability no longer exposes the unused `claimJobForDelivery` mutation; host dispatch remains rooted
in the trusted Package Service entrypoint. The current Guest runtime does not yet construct a
version-safe `JobParameters` bridge, so it returns false and the trusted host JobService asks Android
to reschedule. This avoids silently marking an unexecuted Guest Job as complete.

## Persistence and recovery

Host tests recreate `VirtualSystemServiceStore` from the same persisted file and verify:

- active Notifications retain their host IDs and lifecycle state;
- channel metadata survives Package Service recreation;
- scheduled Jobs retain their host IDs and owner;
- acknowledged callbacks transition to `RUNNING`;
- unacknowledged callbacks stay `SCHEDULED`;
- `finishJob(..., false)` removes the completed record.

The local Android API/JSON test stubs now preserve nested JSONObject/JSONArray state so persistence
recovery is exercised instead of being reduced to a structural assertion.

## Provider coordination split

`RuntimeProviderResourceCoordinator` now owns:

- Provider expiry cleanup;
- Session stop/disconnect/recovery cleanup;
- instance invalidation cleanup;
- best-effort Guest cursor cancellation;
- best-effort Guest file-descriptor close delivery;
- stale-generation exclusion.

`RuntimeBrokerService` no longer contains `applyProviderCleanup`, `closeGuestCursorBestEffort` or
`closeGuestFileBestEffort`. Its size falls from 1,405 lines in M4-T11 to 1,351 lines in M4-T12.

## Typed contract

New typed Parcelable records:

- `VirtualNotificationSnapshot`;
- `VirtualNotificationChannelSnapshot`;
- `VirtualJobSnapshot`.

The scoped system-service AIDL remains free of business `Bundle` payloads. The observer is no longer
`oneway`, because Job execution acknowledgement is synchronous and affects rescheduling semantics.

## Explicit limitations

- Guest `JobService.onStartJob(JobParameters)` execution remains partial until a version-safe
  `JobParameters`/callback identity bridge is implemented.
- Android Job constraints, expedited jobs, persisted jobs, network state and OEM JobScheduler
  variants remain unverified.
- Notification listener callbacks, full channel policy, ranking and OEM field layouts remain
  unverified.
- Persisted Notification payloads are bounded marshalled objects; their cross-version restoration is
  device-gated.
- Shared application UID remains a fundamental security limitation.
