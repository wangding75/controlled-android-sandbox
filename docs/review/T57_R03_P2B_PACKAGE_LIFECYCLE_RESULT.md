# T57-R03-P2B Package Lifecycle Evolution

RESULT: PASS with classified remainder

VA Pro: `NOT_PROVEN`

## Root problem

clear/delete/reinstall was already transactional. Upgrade was
`withImported` plus an immediate unreferenced-revision sweep, so the
previous APK disappeared and rollback was
`INSTALL_SESSION_ROLLBACK_UNSUPPORTED`. Version change, data reset and
identity reset were not distinguished.

## Architecture

`PackageLifecycleTransaction` states:

`INSTALLED` / `UPDATING_PREPARE` / `UPDATING_SWITCH` / `ACTIVE` /
`ROLLBACK_PENDING` / `DELETING` / `DELETED` / `RESETTING`

Independent counters:

- package revision (`sha256`)
- install revision
- data revision
- identity generation

A package-revision change does **not** reset `dataRevision`. Identity
reset advances identity generation and data revision without deleting
the APK or instance row.

Previous revision APKs stay referenced until rollback is discarded, so
`sweepUnreferencedFiles` cannot delete the rollback target.

`SandboxCatalogState.withRestoredRevision` restores the previous record
without mixing new metadata and old code.

## Static

`PackageLifecycleTransactionSelfTest` PASS.

## RD (`RD测试`)

`artifacts/capability-audit/p2b/20260817T120826Z`

| Probe | Result |
| --- | --- |
| import-prepare | PASS |
| clone | PASS `CLONED` |
| identity reset | PASS `IDENTITY_RESET` |
| replace/reimport | PASS |
| rollback | PASS `ROLLED_BACK` |
| prepare after rollback | PASS |

## Remainder

- Split / native-lib / AppComponentFactory upgrade matrix is static +
  same-fixture replace, not a second signed APK lineage.
- In-flight Activity/Service/Provider during `UPDATING_SWITCH` is
  fail-closed by `requireNotInFlight` + existing revision stop barrier;
  no live two-version guest was left running.
- Clone data/PI/SystemService isolation still relies on existing
  per-user instance roots; this campaign did not add a second commercial
  APK lineage.
