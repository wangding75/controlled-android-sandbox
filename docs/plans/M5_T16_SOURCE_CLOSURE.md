# M5-T16 global source closure and residual-gap convergence

## Goal

Audit the complete M5-T15 source baseline against the read-only VA/NBB references and close high-confidence source defects before adding more service hooks. The iteration distinguishes production source wiring from Android build and device evidence.

## Authorized source scope

1. Add a typed V2 Runtime operation transport with protocol version, request correlation, explicit operation allowlist, top-level package/user/session identity and stable errors.
2. Migrate repository-owned Broker, Guest, App and Companion callers to the typed path while retaining legacy Bundle methods only as compatibility adapters.
3. Audit broad method-name matching and add exact-first classification plus regressions for inverse operation names.
4. Close false coverage where data exists without a distinct Framework entry point.
5. Produce a machine-readable residual-gap and large-class inventory.
6. Preserve the frozen 113-category matrix and keep `ref/upstream` read-only.

## Acceptance

- static Android compilation and all Host regressions pass;
- internal direct calls to legacy Runtime Bundle methods equal zero;
- Sensor and Content Observer unregister paths release ownership;
- configured application restrictions require a working RestrictionsManager hook;
- source audit lists remaining source-feasible and device-dependent work separately;
- Android/device evidence remains zero until real toolchain execution.
