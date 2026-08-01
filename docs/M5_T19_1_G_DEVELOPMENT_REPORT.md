# M5-T19.1-G Store Commit Consistency

## Scope

This change closes P2-01 from the M5-T19 global review. It does not add sandbox capabilities or change the frozen 113-item capability matrix.

## Failure reproduced

The original `VirtualSystemServiceStore` persisted a mutation and then submitted an Observer notification to its scheduler. If the scheduler rejected the submission, the caller received a failure even though the durable file already contained the new state. A retry could therefore duplicate or overwrite a mutation that had already committed.

## Fix

- Added an explicit idempotent `closed` state.
- Persistent mutations call `requireOpenForMutation()` before changing in-memory state.
- Missing Scope and namespace objects cannot be created after close.
- Client registration rejects after close; cleanup unregister/delete operations become no-ops after close.
- Observer delivery moved to `VirtualSystemServiceObserverDispatcher`.
- Observer scheduling and Observer callback failures are best-effort and cannot change the result of an already durable mutation.
- Rejected Observer scheduling records `VIRTUAL_OBSERVER_DISPATCH_FAILED:<operation>:<error>` as maintenance evidence.
- `VirtualSystemServiceStore` decreased from 1,500 to 1,415 lines; the existing 1,500-line architecture limit was preserved.

## Deterministic regression coverage

`VirtualSystemServiceStoreCommitConsistencySelfTest` verifies:

1. durable mutation succeeds and survives restart when Observer scheduling is rejected;
2. mutation after close fails with `VIRTUAL_SYSTEM_SERVICE_STORE_CLOSED` before persistence;
3. a mutation already waiting on the Store monitor loses deterministically when close obtains the commit boundary first;
4. restart observes only the last successfully committed value.

The direct regression was repeated 50 times without failure. Host API stubs do not constitute Android Binder Driver, Emulator, or physical-device evidence.
