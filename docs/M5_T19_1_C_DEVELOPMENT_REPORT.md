# M5-T19.1-C Development Report — Binder Binding-Timeout Recovery

## Scope

- Baseline commit: `c9d22eac913c66ed51205436110cd1bad6c11a1f`.
- Baseline task: M5-T19.1-B Native network buffer and FD correctness.
- Branch: `fix/m5-t19-1-rebind-timeout-recovery`.
- Review finding: P1-03, `RebindableServiceConnector` could remain permanently `binding=true` when `bindService()` returned `true` but Android delivered no binding callback.
- Capability matrix expansion: none.
- `ref/upstream` changes: none.

## Root cause

`require()` waited on the active Attempt latch until the caller deadline. When the wait expired it only threw an unavailable exception. It did not complete the Attempt, clear the authoritative `attempt` field, record a binding failure or unbind the `ServiceConnection`. Every later caller therefore joined the same never-completing Attempt and no new `bindService()` call was issued.

## Implementation

### Attempt deadline and timeout ownership

Each Attempt records a monotonic deadline when binding begins. `require()` waits for the smaller of its own request deadline and the active Attempt deadline. A wait timeout enters one authoritative `timeoutAttempt()` path that:

1. verifies the Attempt is still current and incomplete;
2. marks it complete;
3. clears the connector's authoritative `attempt` reference;
4. records `BIND_TIMEOUT`, increments the bounded failure count and schedules exponential retry backoff;
5. releases every waiter through the Attempt latch;
6. claims and unbinds the Android `ServiceConnection` exactly once.

The request-deadline path also cancels the current Attempt. This matters when an earlier rejected bind consumed part of the request budget before a later bind returned `true` without a callback.

### Race handling

- A late `onServiceConnected` callback adapts only transiently. The epoch/current-Attempt recheck rejects it, unlinks its death recipient and closes the adapted capability instead of publishing it.
- Timeout and `close()` serialize Attempt ownership under the connector lock. Only one path can claim the bound connection for unbind.
- A synchronous callback delivered inside `bindService()` remains supported. If another thread closes the connector after that callback but before `bindService()` returns, the return path cannot re-arm the already released connection.
- Successful publication clears failure/backoff state before `require()` loops.
- Existing Binder-death, disconnection, null-binding and adapter-failure recovery paths remain unchanged.

### Retry behavior

Rejected binds still use bounded exponential retry. The direct regression verifies the configured sequence reaches approximately 10 ms, 20 ms and the 40 ms cap before a successful fourth bind. A successful connection resets `consecutiveFailures`, `nextBindAtNanos` and `lastFailure`.

## Regression evidence

`RebindableServiceConnectorSelfTest` directly verifies:

- `bindService=true` with no callback produces `BIND_TIMEOUT`;
- timeout clears `binding`, increments failure state and safely unbinds;
- the next `require()` creates a fresh binding and succeeds;
- a request deadline after an earlier rejected bind cancels the retried no-callback Attempt;
- a late `onServiceConnected` cannot resurrect the timed-out Attempt and its adapted capability is closed;
- `close()` releases a thread waiting in `require()` without deadlock or duplicate unbind;
- close after a synchronous callback but before `bindService()` returns cannot re-arm or double-unbind the connection;
- bounded exponential retry follows the expected 10/20/40 ms progression;
- existing Binder-death reconnection and adapted-capability close semantics remain passing.

The self-test is executed by `tools/static_android_compile.py`, and the dedicated M5-T19.1-C gate checks direct invocation of every new regression method rather than only checking that a test file exists.

## Verification limits

- The tests use deterministic Host stubs and real Java concurrency primitives.
- Android framework `bindService` scheduling, Binder driver timing, process death and OEM service behavior were not executed.
- No Gradle APK, Emulator or physical-device evidence is claimed.

## Status

- Source fix: PASS.
- No-callback timeout recovery: PASS.
- Late callback rejection: PASS.
- Timeout/close synchronization: PASS.
- Exponential retry regression: PASS.
- Android Binder/device evidence: 0.
