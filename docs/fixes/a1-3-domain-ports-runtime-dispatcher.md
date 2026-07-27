# A1-3 Domain ports and runtime dispatcher

## Scope

A1-3 introduces dependency inversion only at boundaries that have an external effect or a replaceable implementation. It does not add component behavior, split Gradle modules, or create one interface per registry.

## Domain ports

| Port | Purpose | Production adapter / implementation |
|---|---|---|
| `Clock` | Supplies monotonic runtime time without binding use cases to Android APIs | `SystemMonotonicClock` |
| `TokenGenerator` | Supplies opaque identities without binding domain state to `UUID` | `UuidTokenGenerator` |
| `AuditSink` | Receives bounded security/use-case outcomes | `RuntimeAuditSink` |
| `SessionMetricsRepository` | Exposes only session capacity/usage/count to diagnostics | `SessionRegistry` |

`SessionRegistry` now receives `TokenGenerator` through its constructor. Empty, oversized, or repeatedly colliding generated session IDs fail closed after a bounded retry count. Runtime code no longer creates session IDs inside the domain registry.

## Dispatcher migration

Runtime Status is the first Broker use case moved out of `RuntimeBrokerService`:

```text
IRuntimeBroker.Stub
  → CallerGuard
  → RuntimeStatusDispatcher
      ├─ Clock
      ├─ RuntimeStatusSource
      ├─ maintenance callback
      └─ AuditSink
          ↓
     BrokerRuntimeStatusSource
```

`RuntimeBrokerService` no longer validates the Runtime Status request, builds `RuntimeStatusSnapshot`, or maps internal errors for this path. It only performs the Binder caller check and delegates to the dispatcher. `BrokerRuntimeStatusSource` is the concrete adapter allowed to read Broker registries.

## Failure behavior

- Invalid protocol requests are rejected before maintenance or registry reads.
- A negative clock value, maintenance failure, null snapshot, or source failure returns stable `INTERNAL_ERROR`.
- Audit failure is isolated and cannot change the use-case result.
- The legacy Bundle status method still delegates to the typed dispatcher and then uses `RuntimeStatusLegacyAdapter`.

## Architecture gates

`scripts/check-ports-dispatchers.py` enforces:

- required Domain ports exist;
- `SessionRegistry` implements the metrics repository and receives a token generator;
- `SessionRegistry` cannot directly use `UUID`;
- `RuntimeStatusDispatcher` cannot import Android or concrete Broker registries;
- `RuntimeBrokerService` cannot build Runtime Status snapshots or retain the old business method;
- Broker time comes from the injected `Clock`;
- the runtime-status Binder method delegates to the dispatcher.

## Deferred

- Other Bundle IPC paths remain unchanged.
- Other token-producing registries have not yet migrated to `TokenGenerator`.
- Persistent package/session repositories are not introduced until persistence ownership is migrated.
- Component dispatchers remain future work after the Runtime Status pattern is proven.
- Real Android Binder, emulator, and device validation remain `not-tested`.
