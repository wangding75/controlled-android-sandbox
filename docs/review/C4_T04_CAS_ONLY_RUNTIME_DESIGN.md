# C4-T04 CAS-only Runtime

## Scope

Prove CAS production cannot ship BlackBox, NBB, Pine, or `xposed_init`.
Reference trees under `ref/` may remain on disk but must not be packaged.
This task does not rewrite `D:\github\all_project\sx`; that tree is freeze
source only.

RD is `RD_BASELINE`. Not DingTalk PASS and not VA Pro equivalence.

## DISCOVER / CLASSIFY

CAS `settings.gradle` already excludes `:Bcore` / `:engine-bb`. Production
`src/main` has no `BlackBoxCore` / `PineXposed` runtime. Missing was a
fail-closed Gradle + APK-content gate, so a future dependency could reintroduce
those runtimes unnoticed. That is `KI-R03-049` (`TEST_EVIDENCE_GAP`).

## Design

Scan, do not copy:

1. Production Gradle includes and `implementation` edges.
2. `app|sandbox-* /src/main` Java/XML/AIDL/native for forbidden tokens.
3. Host APK zip entry names for `libpine`, `libblackbox`, `xposed_init`,
   `niunaijun`.
4. Keep C4-T02 engine smoke as CAS-only runtime evidence.

Forbidden tokens: `BlackBoxCore`, `PineXposed`, `top.niunaijun.blackbox`,
`xposed_init`, `libpine.so`, `libblackbox.so`. Debug trace assertions that
*reject* those tokens are allowed only in `app/src/debug`.

## Acceptance

- Static gate PASS.
- Host APK has no forbidden zip entries.
- `c4-t02-engine` CAS-only smoke PASS on `RD测试`.
- Migrated C4-T03 data path is not deleted by this task.
- `va_pro_equivalent` remains `NOT_PROVEN`.
