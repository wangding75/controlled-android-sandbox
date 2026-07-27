# A1-2 typed runtime-status contract

## Scope

A1-2 establishes the first versioned, typed Binder path without changing runtime business behavior or Gradle modules.

## Typed path

- `RuntimeStatusRequest` carries a positive protocol version and a bounded request ID.
- `RuntimeStatusResult` is a success/error envelope.
- `RuntimeStatusSnapshot` groups immutable, internally consistent resource counters through a named builder.
- `SandboxError` carries a bounded error code, message and retryability flag.
- `IRuntimeBroker.runtimeStatusV2` accepts and returns only these typed Parcelable models.
- The App uses `runtimeStatusV2`; it no longer consumes the legacy Bundle path.

## Compatibility

`IRuntimeBroker.runtimeStatus()` remains temporarily available for old callers. The Binder implementation delegates to the typed path and converts the typed result through `RuntimeStatusLegacyAdapter`. Business logic is not duplicated in the legacy method.

## Validation

- Null requests fail with `INVALID_REQUEST`.
- Unsupported protocol versions fail with `UNSUPPORTED_PROTOCOL`.
- Blank or oversized request IDs are rejected during construction and unparceling.
- Successful results cannot carry errors; failed results must carry errors.
- Negative counters are rejected.
- Typed request/result classes do not depend on `Bundle`.

## Deferred

- Other Binder methods remain Bundle based and will migrate incrementally.
- Legacy runtime status removal requires a later protocol-major decision.
- Android device-side AIDL parceling remains `not-tested` until SDK/device validation resumes.
