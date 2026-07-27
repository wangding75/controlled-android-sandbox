# M3 Source-Complete Gate

This gate is intentionally separate from the Android Emulator release gate.
It proves that source-level contracts, state ownership, failure handling, and regression tests exist before device work begins. It does **not** claim third-party application compatibility.

## Required source-level capabilities

- Explicit Activity task and lifecycle model.
- Started and bound Service ownership, restart and process-death model.
- Manifest and dynamic Receiver registries with session cleanup.
- Provider authority namespace and instance isolation.
- URI read/write grant lifecycle.
- Guest declared-process and isolated-process planning.
- Versioned Binder contract and stale-generation rejection.
- Virtual package, user, UID and filesystem persistence.
- Framework service proxy matrix with rollback diagnostics.
- Native file, network and dynamic-library hook implementation.
- Fixture coverage for all component and process modes.

## Gate semantics

`SOURCE_COMPLETE` means every item above has executable offline verification or a strict structural gate.
`M3_RELEASE_PASS` additionally requires real Android build, install, component execution, process evidence, WebView/Native evidence and the stability run.
