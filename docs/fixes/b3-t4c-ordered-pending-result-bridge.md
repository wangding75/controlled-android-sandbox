# B3-T4C — Ordered Broadcast PendingResult Bridge

## Goal

Close the source-level gap between the Broker ordered-broadcast result chain and an ordinary
`BroadcastReceiver` implementation. The Broker remains authoritative for ordering, result state,
timeout and lifecycle cleanup; the Guest receives a controlled platform `PendingResult` so
`setResultCode`, `setResultData`, `setResultExtras`, `abortBroadcast`, `clearAbortBroadcast` and
`goAsync().finish()` can feed the same Broker result chain.

## Broker authority

Each ordered Receiver delivery receives a one-shot random token. The token is bound to:

- target package and virtual user;
- target Session ID and generation;
- Receiver class;
- issue time and an absolute monotonic deadline.

The Broker registry has a maximum of 256 active tokens and a maximum per-Receiver timeout of ten
seconds. Completion is atomic and has exactly one winner. Replays, wrong identity, late callbacks,
unknown tokens and malformed results are rejected. Malformed results terminally cancel the matching
token after its identity has been validated, so the ordered chain does not wait for an unnecessary
timeout.

Session stop, failed recovery, Binder disconnect, virtual-instance stop and Broker destruction cancel
matching pending tokens and unblock the waiting ordered chain. Terminal records are retained briefly
so replay and late-completion decisions remain deterministic.

## Guest bridge

The Broker sends the token, deadline and an `IOrderedReceiverCompletion` Binder with the Receiver
request. Before `onReceive`, the Guest reflectively constructs the platform
`BroadcastReceiver.PendingResult` and installs it with `setPendingResult`.

Two completion paths are supported:

1. **Synchronous Receiver** — after `onReceive`, the Guest reads the current PendingResult and reports
   its result code, data, string extras and final abort state.
2. **`goAsync()` Receiver** — `goAsync()` clears the Receiver's current PendingResult. When the app
   later calls `PendingResult.finish()`, the Framework ActivityManager proxy intercepts
   `finishReceiver`, captures the final result and reports it to the Broker.

The finish token uses a sandbox marker Binder. A replayed or late marker token is always consumed by
the proxy and is never forwarded to the real host ActivityManager. The Guest also schedules local
cleanup at the Broker deadline, while the Broker independently enforces the authoritative timeout.

## Fail-closed limits

- Result extras are string-only and use the existing ordered-result size limits.
- A missing callback Binder, token, identity field or deadline rejects bridge installation.
- An unavailable or changed hidden PendingResult constructor rejects ordered delivery; the code does
  not silently fall back to unordered semantics.
- Callback transport failure is swallowed at the Framework interception boundary so a custom token
  cannot escape to host AMS; the Broker then reaches its timeout/cancellation result.

## Verification

Local tests cover synchronous result capture, abort and clear-abort, asynchronous finish interception,
late-token swallowing, local timeout cleanup, Broker timeout, wrong generation, replay, malformed
result cancellation, capacity, timeout clamp, instance/session cleanup and 16-thread single-winner
completion.

## Device boundary

This stage uses hidden platform constructor and method reflection. Local Android API stubs prove source
wiring and lifecycle behavior only. Hidden-API enforcement, exact constructor signatures on each API
level, real ActivityManager proxy interception, Binder identity and OEM behavior remain `not-tested`
until emulator and device validation is performed.
