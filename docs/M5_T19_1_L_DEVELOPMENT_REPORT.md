# M5-T19.1-L Cached System-Service Capability Recovery

- Finding: P2-05 cached virtual system-service capability death caused one unnecessary Guest attach failure.
- Baseline: `0a7a769836cb33d9d701f53ffe7bd61d48410101`.
- Scope: `RuntimeSystemServiceCoordinator` only; no new service hooks or capability-matrix entries.

## Implemented behavior

- A dead cached `IVirtualSystemServiceSession` is removed and closed before the current attach continues.
- The same attach opens and publishes one replacement session instead of returning `VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DEAD`.
- Coordinator synchronization ensures concurrent attach callers share one replacement open.
- A newly opened session that is already dead is closed and rejected with `VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DEAD_AFTER_OPEN`; it is never cached.
- Coordinator close remains idempotent for cached sessions and the package-service client owner.

## Evidence boundary

The regression is verified by Host Binder stubs and deterministic barriers. Android Binder Driver, package-service process death, emulator, and physical-device behavior are not claimed.
