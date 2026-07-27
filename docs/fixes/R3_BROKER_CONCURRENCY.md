# R3 Runtime Broker Concurrency Fix

## Scope

- H-06 Binder-thread races in broker `HashMap` state.

## Changes

- Moved prepared specs and route payloads into `BrokerStateStore` backed by `ConcurrentHashMap`.
- Cursor authorization initially shared this store during R3, then B3-T3C removed that legacy path; `BrokerCursorRuntime` is now the sole Broker Cursor authority.
- All stored `Bundle` values are defensively copied on write and read.
- Route ticket and payload issue/consume/purge operations now share one lock.
- Failed host Activity launch revokes both the ticket and payload.
- Guest connection slots use `ConcurrentHashMap` while retaining serialized bind/unbind transitions.
- Cursor and route cleanup use compare-and-remove rather than unsafe mutable iterators.

## Verification

- 16 concurrent consumers: exactly one can consume a route payload.
- 12 threads execute 7,200 route and cursor lifecycles without leaks.
- Defensive-copy tests prevent caller mutation of broker state.
- Full repository verification and static Android compilation.
