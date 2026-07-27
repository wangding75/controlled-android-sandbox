# B3-T3B Provider CRUD / Call broker transaction routing

Baseline: `main@ea8745b0dc44e488aebc7a9830a27d03fe99375f`

## Scope

- Make the Broker resolve Provider CRUD, query, type and `call` operations from the authoritative virtual-user authority registry.
- Reject unknown `PROVIDER_*` operations before any Guest Binder call.
- Bind cross-instance Provider calls to a live caller `sessionId + generation` rather than trusting caller package fields.
- Route the operation to the authority owner's exact Provider session, generation, process and component.
- Replace caller-supplied target component/process values with Broker-owned registration data.
- Authorize operations through one of three explicit bases: `OWNER`, `EXPORTED`, or a live `URI_GRANT`.
- Treat `ContentProvider.call` as read/write because arbitrary provider methods may mutate state.
- Require the Provider owner to issue URI grants; another running virtual App cannot grant access to an authority it does not own.
- Add a bounded Broker audit ledger for successful, Guest-failed, Broker-failed and denied operations.
- Expose audit identity and permission basis on successful/Guest-failed transaction responses.

## Transaction rules

1. Validate the operation whitelist.
2. Authenticate the caller instance. Cross-instance requests require a live caller session and exact generation.
3. Resolve the authority in the requested virtual-user namespace.
4. Reject a caller-supplied target package that disagrees with the registered authority owner.
5. Validate `content://` scheme and exact URI authority.
6. Validate owner/exported/URI-grant permission before dispatch.
7. Resolve the exact target Guest session from Broker registration, not request process fields.
8. Call the Guest Provider.
9. Commit Broker cursor-access state only after a successful Guest query.
10. Record exactly one terminal audit result.

## Added Provider operation

`PROVIDER_CALL` carries:

- `providerAuthority`
- `providerMethod`
- optional `providerArgument`
- optional `providerExtras`

The result is returned under `providerResult`.

## Verification

- Owner, exported and URI-grant authorization paths.
- Private Provider denial without a grant.
- Caller package spoof and stale caller generation denial.
- Wrong target package and wrong virtual-user namespace denial.
- URI authority mismatch rejection.
- Unknown Provider operation rejection.
- Provider `call` method requirement and read/write permission classification.
- URI grant issuer ownership validation.
- Guest failure auditing without successful Broker state commit.
- Bounded 256-entry audit retention.
- Full repository verification through `scripts/verify-all.sh`.

## Deferred device-dependent boundary

Android `ContentResolver`, `IContentProvider`, Binder identity, system URI grants and real Provider process behavior remain intentionally unverified until device testing resumes.
