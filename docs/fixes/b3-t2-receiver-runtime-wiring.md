# B3-T2 Receiver runtime production wiring

Baseline: `main@5c3cfd1aaf7b630adf1bf1017abf24edbb02590d`

## Scope

- Make the broker registry the authority for dynamic Receiver ownership and action resolution.
- Reserve a registration before invoking the Guest process.
- Roll back the broker reservation when Guest registration fails or the Binder call throws.
- Validate Receiver ownership before asking the Guest to unregister it.
- Commit broker removal only after successful Guest unregister.
- Scope Receiver IDs by `sessionId + generation + receiverId`, allowing different virtual users and sessions to reuse the same local ID.
- Preserve virtual-user, exported, action, session, and generation isolation during broadcast resolution.
- Remove all dynamic registrations when their exact Guest session/generation ends.

## Security and consistency rules

- A Receiver ID is local to one Guest session generation, not global across the sandbox.
- A caller cannot unregister another session's Receiver even when the local ID matches.
- Non-exported registrations are visible only to their owning session.
- External delivery resolves exported registrations only.
- Action values are trimmed and stored as immutable normalized sets.
- Duplicate concurrent registration for the same owner and ID has exactly one winner.

## Verification

- Two virtual users can register the same Receiver ID without collision.
- Virtual-user and non-exported session boundaries are enforced.
- Exported internal and external resolution is deterministic.
- Failed Guest registration rolls back broker state idempotently.
- Wrong-owner unregister fails before Guest mutation.
- Session cleanup removes only the exact generation's registrations.
- Sixteen concurrent duplicate reservations produce one success and no leak.
- Full repository verification includes `BrokerReceiverRuntimeSelfTest`.

## Remaining device-dependent boundary

The broker model does not prove Android broadcast compatibility. API 29-36 emulator testing is still required for process startup, ordered broadcasts, permission-protected broadcasts, background execution limits, manifest receivers, and framework-originated broadcasts.
