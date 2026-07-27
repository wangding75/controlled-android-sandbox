# B3-T3E-2 Provider Batch Operations

## Scope

This stage adds bounded Provider batch routing and execution without relying on an Emulator or physical device.

## Production behavior

- `PROVIDER_APPLY_BATCH` is a Broker-routed Provider transaction operation.
- Each request contains 1–128 operation Bundles and is bound to one Provider Authority and virtual-user namespace.
- Supported standard operation types are `INSERT`, `UPDATE`, `DELETE` and `ASSERT`.
- Standard operations are translated to Android `ContentProviderOperation` and executed through the target Provider's `applyBatch` implementation.
- `CALL` inside a batch is accepted only when the target Provider explicitly implements `AtomicProviderBatch`; otherwise the entire request fails closed before execution.
- Every operation URI is independently checked against the Provider Authority and, for private cross-instance access, against its own URI Grant flags.
- Read/write flags are aggregated for audit while permission checks remain per-operation.
- Per-operation wire payload is limited to 64 KiB, the batch to 512 KiB and the operation count to 128.
- Unsupported Bundle value types, deep nesting, foreign Authorities and unknown operation types are rejected before Guest execution.
- Guest results must contain one result Bundle per operation and remain within the same 512 KiB wire limit.
- Validation and Guest failures return `providerBatchFailureIndex`; Broker audit entries retain the same index.

## Atomicity boundary

Android standard operations use the Provider's `ContentProvider.applyBatch` contract. A Provider controls its own underlying database transaction behavior. Mixed batches containing `CALL` require the explicit `AtomicProviderBatch` extension, whose implementation contract requires all-or-throw behavior and may report an exact operation index with `AtomicProviderBatchException`. The Broker never reports a partial result as success.

## Local evidence

- `ProviderBatchRuntimeSelfTest` covers standard execution, bounded payload validation, Authority isolation, custom atomic CALL batches, malformed results and fail-closed behavior.
- `BrokerProviderRuntimeSelfTest` covers mixed read/write classification, per-operation URI Grant checks, partial grant denial and audit failure indexes.
- `RuntimeBrokerService` validates successful Guest results before audit completion.

## Deferred device evidence

Android `ContentResolver.applyBatch`, OEM Provider transaction behavior, real Binder payload limits and third-party Provider database rollback remain `not-tested` under the user-approved device-test deferral.
