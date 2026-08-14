# T57-R02 Main Review Revalidation

## Result

`RESULT: BLOCKED`

The branch is `feature/t57-runtime-deep-review-observability`. The latest T56
guest-runtime commit was merged without rebase/reset/squash:

- merge base before sync: `bfa436f14044c47c51809ae8d95f3c049b9d0fa5`
- synced T56: `85db1d43...`
- review merge: `5ad849be8e5cfde38b24e77993e72926ca6ff9d8`

Repository static compile, Android APK builds, self-tests, and Activity/Service/
package/PendingIntent checkers pass. The device-lab four-APK build also passes.

## RD API32 evidence

The `RD测试` MuMu instance was resolved dynamically at runtime and verified as
API 32 / Android 12, model `22041211A`, device `rubens`. The diagnostic evidence
under `artifacts/m5-device-lab-rd-diagnostic-slot-check/` records the session
serial, boot ID, manager metadata, APK hashes, and Git HEAD.

The bounded diagnostic run passed Activity launch/resume, Service start/stop,
Provider preparation, 32-bit companion probes, teardown, a 30-second stability
window, and simultaneous user0/user1 Guest slots. Direct clear/delete replay also
passed with a fresh generation and process slot after deletion.

## Review rules

Static source/self-test evidence and device evidence are separate gates. `PREPARED`,
host trampoline completion, or a Java proxy self-test is not Framework ownership.
Android 13–16 results are not inferred from an API32 simulator.

The final result remains BLOCKED because the dedicated real PendingIntent
`IIntentSender` fixture has not been run, the bounded diagnostic run is not the
formal 1200-second stability gate, and API 33–36 remain untested.

## Changes revalidated

- Activity manifest identity uses the declared component alias and projects task-contract fields into `ActivityInfo`.
- PendingIntent interception uses positional permission semantics and exposes a descriptor-bearing Binder transport; real cross-process `IIntentSender` evidence remains pending.
- clear/delete stop the runtime generation before destructive catalog/data mutation and return explicit partial-cleanup failures.
- `<queries>` package/provider/intent declarations are parsed and carried through package-state snapshots into guest metadata.
- Runtime event records carry a shared trace domain, launch/binder identifiers when supplied, virtual user, generation, slot, physical PID, and thread TID.
- Hardcoded app/SDK-specific native probe names were removed.
- The 32-bit companion CMake target now includes the shared JNI pending-exception probe source.
- Device-lab process evidence recognizes the current T57 fixture-process naming while retaining the legacy slot path.
