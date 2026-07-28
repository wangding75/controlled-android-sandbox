# M4-T13 Guest JobService execution bridge

## Status

SOURCE/HOST PASS candidate. Device validation remains intentionally deferred.

## Objective

Replace the M4-T12 acknowledgement-only Job callback with a bounded execution bridge that invokes the owning Guest `JobService`, preserves host/guest Job identifiers, and routes `jobFinished` and stop/reschedule decisions without exposing the host JobScheduler callback Binder.

## Implemented flow

```text
Android JobScheduler
→ trusted VirtualJobService (:sandbox_server)
→ bounded VirtualJobParametersSnapshot
→ Package Service persistent Job state
→ owning package/user/process/generation observer
→ GuestJobServiceBridge
→ Guest JobService.onStartJob/onStopJob
→ scoped IVirtualJobExecution.finish
→ trusted IHostJobCallback
→ VirtualJobService.jobFinished
```

## Typed contracts

- `VirtualJobParametersSnapshot` carries bounded, version-neutral public Job data.
- `IVirtualJobExecution` is a one-shot capability bound to Guest Job ID, Runtime generation and dispatch token.
- `IHostJobCallback` is held only by Package Service and trusted `VirtualJobService`.
- `IVirtualSystemServiceSession.finishJob` was removed; Guest code cannot finish arbitrary persisted Jobs by integer ID.

## State machine

```text
SCHEDULED
→ DISPATCHING
→ RUNNING
→ removed, or SCHEDULED when rescheduling
```

Package Service validates the owning scope, virtual process and Runtime generation before dispatch. Host callback death, Runtime observer replacement/death and the bounded execution timeout restore the Job to `SCHEDULED`. Completed or stopped execution capabilities are invalidated and cannot affect a newer execution.

## Guest execution

`GuestJobServiceBridge`:

- resolves the declared service from the stored Guest `JobInfo`;
- requires the component to extend `JobService`;
- creates and attaches the Guest service to the isolated Guest Context;
- invokes `onCreate`, `onStartJob`, `onStopJob` and `onDestroy` on the Guest main thread;
- reconstructs `JobParameters` through a version-adapted reflective constructor;
- provides a raw, restricted `IJobCallback` Binder that accepts only `jobFinished`;
- maps synchronous completion, asynchronous completion, stop and Runtime shutdown to the scoped execution capability.

## Bounds and failure handling

- Each Parcelable payload remains limited to 512 KiB.
- Trigger URI/authority collections are item- and aggregate-length bounded.
- Dispatch requires a live trusted host callback Binder.
- Duplicate active execution is rejected.
- Runtime generation and dispatch token must match.
- Guest rejection leaves the Job `SCHEDULED` and asks Android to reschedule.
- Start/stop failures fail closed and request rescheduling.
- Package Service restart converts stale `DISPATCHING`/`RUNNING` records back to `SCHEDULED`.
- Instance deletion invalidates active execution and completes the trusted Host callback without rescheduling.

## Host-side evidence

- Typed contract Parcelable round-trip.
- Persistent state-machine start, asynchronous finish, stop/reschedule and stale capability rejection.
- Guest `JobParameters` Guest-ID reconstruction.
- One-shot `jobFinished` callback behavior.
- Static Android source compilation using the repository API stubs.
- Existing Package, permission, component, Framework and Native host tests remain passing.

## Remaining limitations

- Hidden `JobParameters` constructors and callback transaction codes require Android-version device validation.
- Work item dequeue/complete is not yet bridged.
- Network, charging, idle and expedited constraints remain represented by the host `JobInfo`; Guest execution does not emulate every Android-version field.
- Real Android Binder thread/main-thread behavior has not been tested.
- Device reboot persistence and OEM JobScheduler behavior remain unverified.
