# T57-R02 Main Review Revalidation

## Result

`RESULT: BLOCKED`

The branch is `feature/t57-runtime-deep-review-observability`. The latest T56 guest-runtime commit was merged without rebase/reset/squash:

- merge base before sync: `bfa436f14044c47c51809ae8d95f3c049b9d0fa5`
- synced T56: `85db1d43...`
- review merge: `5ad849be8e5cfde38b24e77993e72926ca6ff9d8`

The repository-wide static compile and self-test suite now pass, including `FrameworkProxySelfTest`. The process-record failure found during the first run was corrected by making the reflective process-record fields accessible when safe. The repository checkers for Activity/Task, Service lifecycle and package lifecycle also pass.

The only attached ADB device that could be queried was `127.0.0.1:16416` (`API=32`, model `22041211A`, device `rubens`). Its runtime metadata does not identify the instance as `RD测试`; the dynamic resolver therefore rejects it. The offline device is also rejected. No RD real-path result is claimed.

## Review rules

Static source/self-test evidence and device evidence are separate gates. `PREPARED`, host trampoline completion, or a Java proxy self-test is not Framework ownership. Android 13–16 results are not inferred from an API32 simulator.

## Changes revalidated

- Activity manifest identity now uses the declared component alias and projects task-contract fields into `ActivityInfo`.
- PendingIntent interception now uses positional permission semantics and exposes a descriptor-bearing Binder transport; real cross-process `IIntentSender` evidence remains pending.
- clear/delete now stop the runtime generation before destructive catalog/data mutation and return explicit partial-cleanup failures.
- `<queries>` package/provider/intent declarations are parsed and carried through package-state snapshots into guest metadata.
- runtime event records now carry a shared trace domain, launch/binder identifiers when supplied, virtual user, generation, slot, physical PID and thread TID.
- hardcoded app/SDK-specific native probe names were removed.
