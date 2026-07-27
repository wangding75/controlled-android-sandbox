# B3-T3C Provider Cursor lease hardening

Date: 2026-07-26

## Scope

This stage makes Cursor paging Broker-authoritative without requiring Emulator or device evidence.

- Broker reserves the Cursor token before the Guest query executes.
- Query success commits one Broker lease; Guest or Broker failure rolls the reservation back.
- Caller instance, Caller Session/generation, target instance and target Session/generation are immutable lease identity.
- Page requests must use the exact next offset and sequence. Replays, skips and concurrent duplicate pages fail closed.
- Close and cancel are terminal operations and require the current sequence.
- Lease expiry, Caller/target Session death and instance removal invalidate Broker state and best-effort close the Guest Cursor.
- Guest Cursor transport enforces active-lease, column, total-row, page-size, encoded-page and encoded-cell limits.
- Expired, cancelled, oversized or failed Cursors close the physical `Cursor` object.
- Large result sets remain paged and are never materialized into one Binder `Bundle`.
- Legacy weak Cursor access entries were removed from `BrokerStateStore`; `BrokerCursorRuntime` is the sole Broker authority.
- Provider instance IDs are consistently formatted as `u<virtualUserId>:<packageName>`.

## Local evidence

- `CursorLeaseRegistry` sequence, owner, expiry and replay tests.
- `ProviderCursorTransportSelfTest` covers 10,000-row paging, byte limits, expiry, cancellation, capacity and 16-thread duplicate-page contention.
- `BrokerCursorRuntimeSelfTest` covers Broker-issued tokens, exact Session/generation binding, capacity, expiry, invalidation and 16-thread single-winner page reservation.
- Repository-wide `./scripts/verify-all.sh` remains the stage gate.

## Explicitly skipped

Per user instruction, this stage does not claim Android Binder, ContentResolver, Emulator, device, OEM ROM or third-party App compatibility. Device evidence remains `not-tested`.
