# B3-T4B Implicit Receiver matching and ordered-broadcast source model

Baseline: `main@d04ed2114cf9c189e55a8b30e923a62e94f47cf8`

## Scope

- Extend manifest parsing from action-only filters to priority, categories, scheme, host, exact path,
  path prefix, simple path pattern and MIME metadata.
- Maintain a Broker-owned action index partitioned by virtual user instead of scanning every package.
- Resolve implicit manifest Receivers with deterministic priority/package/class ordering.
- Enforce Receiver `exported`, Receiver permission and sender-required Receiver permission rules.
- Preserve explicit manifest Receiver activation and dynamic Receiver delivery.
- Add bounded broadcast payload and maximum Receiver-match limits.
- Add an Android-independent ordered result chain with result code, result data, string extras,
  abort and clear-abort semantics.
- Keep ordered failure policy inside a dedicated Dispatcher rather than `RuntimeBrokerService`.

## Matching rules

- Action must match exactly.
- Every category on the broadcast must be declared by the selected filter.
- A filter without data constraints accepts only an Intent without URI or MIME data.
- MIME supports exact values and `type/*`, `*/subtype`, `*/*` wildcards.
- Scheme and host comparisons are case-insensitive.
- Paths support exact, prefix and a bounded simple `*` glob model.
- Multiple `<data>` entries in one filter are aggregated into scheme, authority, path and MIME sets.
- Results are sorted by priority descending, then package and Receiver class ascending.
- One implicit resolution returns at most 128 Receivers.

## Permission model

- Cross-package delivery requires an exported Receiver.
- A Receiver-declared permission must be requested by the sender package in the current source model.
- A sender-required Receiver permission must be requested by the target package.
- Cross-virtual-user implicit delivery is fail-closed.
- Permission failures remove a candidate from an implicit result instead of exposing protected component metadata.

## Ordered source model

`ManifestBroadcastDispatcher` processes the already sorted route list serially. Each successful delivery may
produce a `ResultUpdate`; the Broker applies it to an immutable `OrderedBroadcastState`. A Receiver can set a
new result code/data/extras and can abort or clear its own abort before returning. Delivery failures either
continue or stop the chain according to `broadcastStopOnFailure`.

The Guest currently receives action, category, URI and MIME fields and echoes the current ordered result state.
The Broker model can consume a Guest result update, but this stage does **not** claim that an arbitrary Android
`BroadcastReceiver` can yet call platform `setResultCode`, `setResultData`, `setResultExtras` or
`abortBroadcast` successfully when manually instantiated. Real `PendingResult` integration remains device work.

## Limits

- 4,096 indexed package/user records.
- 1,024 Receivers per package.
- 128 filters per Receiver.
- 128 actions per Receiver/filter.
- 128 categories and 128 data rules per filter.
- 128 matched Receivers per broadcast.
- 512 KiB conservatively estimated request payload.
- Ordered result data and string extras have independent bounded sizes.

## Verification

- Binary manifest parser assertions for priority, category, URI and MIME metadata.
- Action-index replacement and package-removal stale-entry tests.
- Action/category/host/path/MIME positive and negative matching tests.
- Receiver and sender-required permission filtering.
- Stable priority ordering and target-package restriction.
- Cross-user rejection and 128-result fail-closed limit.
- Ordered result propagation, abort/clear-abort and continue/stop-on-failure tests.
- 512 KiB payload admission test.
- Architecture gate preventing full package scans or policy logic from returning to the Broker Service.

## Remaining boundary

No simulator or device testing was performed. Protected system broadcasts, sticky broadcasts, platform
background-execution limits, Android `BroadcastReceiver.PendingResult`, framework-originated broadcasts,
real Binder parcel sizes, OEM behavior and dynamic+manifest combined Android ordering remain `not-tested`.
