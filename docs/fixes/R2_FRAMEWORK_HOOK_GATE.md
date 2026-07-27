# R2 Mandatory Framework Hook Gate

## Scope

- H-04 mandatory Framework hook failures allowed Guest READY.

## Changes

- Classified PackageManager, AMS, ATMS, AppOps, and Permission hooks as mandatory.
- A mandatory failure rolls back every already-installed hook before Guest Application creation.
- Guest preparation rejects BLOCKED hook reports before `Application.onCreate()`.
- Optional Notification, JobScheduler, and Storage failures produce explicit `DEGRADED` / `PREPARED_DEGRADED` status.
- Cached Guest preparation preserves degraded status instead of returning an unconditional READY equivalent.

## Verification

- READY, DEGRADED, and BLOCKED policy tests.
- Mandatory failure rejection test.
- Full repository verification and static Android compilation.
