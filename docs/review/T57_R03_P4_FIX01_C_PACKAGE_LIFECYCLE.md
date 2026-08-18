# T57-R03-P4-FIX01-C — Package revision and identity reset

RESULT: `AUTHORITY_BOUND`

## Contract

`resetIdentity` is **scheme A**: a real identity reset of runtime
authorities, not a metadata counter alone.

It still advances `identityGeneration` and `dataRevision` (existing
transaction). It now also:

- stops **every** catalog virtual user for the package
- deletes SystemService / device / interaction / network / environment
  scopes for each of those users

`rollbackPackage` uses the same all-user stop. Revision switch already
used `stopGuestBeforeRevisionCommit` across catalog users.

user0 is no longer hard-coded on rollback/reset.

## Lineage fixtures

`fixture-lifecycle` product flavors:

| Flavor | versionCode | versionName | Extra component |
| --- | ---: | --- | --- |
| v1 | 1 | 1.0-lifecycle | `LifecycleActivity` |
| v2 | 2 | 2.0-lifecycle | + `LifecycleV2Activity` |

Same `applicationId` and the repo release signing lineage.

## Tests

`PackageLifecycleTransactionSelfTest` still proves v1→v2 switch,
rollback hash/version restore, and identityGeneration increment.

RD runner `tools/capability/run_p2b_rd.py` now also imports the
lifecycle lineage when those APKs exist: v1 import/clone/launch,
v2 replace/launch, rollback, identity reset.

## Remainder

- Provider grant / durable PI token wipe is implied by stopping the
  session and deleting SystemService scopes; a dedicated PI grant
  census after reset is FIX01-D adjacent evidence.
- Split/native/resource deltas in the lineage fixture are not present
  (Java-only APK pair).
