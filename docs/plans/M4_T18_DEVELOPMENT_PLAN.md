# M4-T18 Development Plan — Device-Test Preflight Source Closure

## Baseline

- Formal starting point: `2b97734f6a86e947ec2e2b2bfad0b528e6658f55` (`m4-t17-source-pass`).
- M4-T18 is a source-closure stage. Emulator and physical-device execution are explicitly outside this iteration.
- Every batch must pass its scoped source, contract, host, native and reproducible-package gates before fast-forwarding into local `main`.
- Every completed batch produces a complete source ZIP, a full Git bundle, an incremental patch, a report, a verification log and SHA-256 checksums.

## Frozen acceptance boundary

M4-T18 closes the repository for device testing by completing all of the following without adding new product scope:

1. Full-repository review and duplicate-code cleanup.
2. Split remaining God Classes where responsibilities can be separated without changing public behavior.
3. Audit all AIDL contracts for unbounded or newly introduced `Bundle` business payloads.
4. Audit Guest-facing queries for Host-data fallback.
5. Audit Binder-owned capabilities for PID, UID, Session, Generation and APK Revision binding.
6. Audit persistent state for input/file/count limits and corruption handling.
7. Audit state-changing writes for rollback on persistence failure.
8. Audit Binder/process death cleanup for every owned resource.
9. Audit APK Revision and instance removal cleanup for every persistent resource.
10. Recalculate repository structure, file count, source lines and VA/NBB evidence matrix.
11. Generate a device-test preflight unfinished-capability list.
12. Freeze a clean `m4-t18-source-pass` source baseline.

## B1 — Structure, persistence and contract closure

**Execution status: PASS**

### Scope

- Extract durable JSON/file persistence from `VirtualSystemServiceStore` into dedicated components.
- Add bounded store-file and payload limits, checksum validation, corruption quarantine and legacy schema compatibility.
- Keep all current resource behavior and public Binder contracts stable.
- Add a repository-wide AIDL/Bundle audit gate that rejects new unapproved Bundle business contracts.
- Add a God-Class and duplicate-implementation inventory with explicit thresholds and approved exceptions.

### Acceptance

- `VirtualSystemServiceStore` no longer owns raw file I/O or JSON envelope integrity.
- Existing schema 1–5 state remains readable.
- New writes are atomic, bounded and checksum protected.
- Oversized/corrupt input fails closed and is quarantined.
- Existing M4-T14–T17 regression gates remain PASS.

## B2 — Ownership, rollback and cleanup closure

**Execution status: PASS**

### Scope

- Add a machine-readable lifecycle/ownership audit for persistent and Binder-owned resource registries.
- Close any discovered missing capacity, rollback, Binder-death, process-death, APK Revision or instance-removal cleanup path.
- Add Guest-query no-Host-fallback source gate and explicit reviewed exception list for identity-proxy pass-through calls.
- Add PID/UID/Session/Generation/APK Revision binding source gate for privileged Binder entry points.

### Acceptance

- Every persistent resource domain has count/file/payload bounds.
- Every write path either commits durably or restores the exact pre-change in-memory state.
- Every Binder-owned resource has an explicit disconnect/death cleanup owner.
- Guest package/task/system-service queries cannot expose Host data on failure.
- All exceptions are documented, narrow and covered by source tests.

## B3 — Final review, evidence and freeze

### Scope

- Perform the final full-repository review and remove remaining safe duplicate helpers.
- Re-run all source, architecture, contract, host, native, M3 and reproducible-source gates.
- Recalculate capability evidence and VA/NBB comparison.
- Produce the final unresolved-device-capability list and emulator import/test prerequisites.
- Freeze local `main` and tag `m4-t18-source-pass`.

### Acceptance

- Local `main` is clean and contains B1, B2 and B3.
- All locally executable gates PASS.
- Device evidence remains explicitly separate and is not inferred from source tests.
- Complete source ZIP and Git bundle independently reproduce final `HEAD`.
