# B3-T3A Provider authority lifecycle

Baseline: `main@5e7a3abe0dcf1773cf11eed4ae35d8dc8b10a61a`

## Scope

- Make `RuntimeBrokerService` the production authority for Provider authority ownership.
- Reserve all semicolon-separated authorities before invoking Guest `ContentProvider.attachInfo`.
- Roll back only authorities created by the failed prepare transaction.
- Enforce one authority owner per virtual user while allowing the same authority in different virtual users.
- Bind authority ownership to `instanceId + sessionId + generation + processName + component`.
- Reject Provider operations from stale or foreign sessions before calling the Guest process.
- Require `content://` URIs to match the requested Provider authority.
- Rebind exact Provider ownership during recoverable Guest generation changes.
- Remove Provider authorities on explicit session or instance shutdown.

## Consistency and security rules

- Registration of multiple authorities is atomic.
- Repeating the same registration for the same session/generation is idempotent.
- A different package, component, process, session, generation, or exported policy cannot take an existing authority.
- Rollback is owner-scoped and idempotent; it cannot remove another virtual user's authority.
- Stale generation access fails before Guest Provider mutation.
- Device compatibility is not inferred from local registry tests.

## Verification

- Domain tests cover atomic registration, idempotency, same-user collisions, cross-user isolation, recovery and cleanup.
- Runtime tests cover prepare rollback, wrong-owner denial, URI-authority mismatch, generation rebind and instance cleanup.
- Sixteen concurrent packages competing for one authority in the same virtual user produce exactly one winner.
- Full repository verification includes `BrokerProviderRuntimeSelfTest`.

## Deferred device-dependent boundary

Real Android validation remains deferred for `ContentResolver`, system URI grants, Binder provider acquisition, Provider process startup and OEM/API-level behavior.
