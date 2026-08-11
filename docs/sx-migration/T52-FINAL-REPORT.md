T52 RESULT: IN_PROGRESS (continuation in progress; prior BLOCKED verdict withdrawn)

# T52 final report — SX migration to Controlled Android Sandbox

## Executive result

The prior BLOCKED conclusion was invalid because it stopped on ordinary Runtime/compatibility defects. T52 continues here without rollback. The SX business surface is migrated behind the Controlled Android Sandbox SDK/adapter boundary; generic Activity recovery, Guest main-thread/Binder routing, teardown, and task-safe acceptance entry have now been repaired and are being re-regressed on SX.

Current fresh SX evidence already shows Stage A, Quark 10x/5min, and DingTalk 10x/5min passing. The report remains `IN_PROGRESS` until final source review, backup/restore verification, final commit push, and worktree freeze are complete.

## Baseline and source state

- branch: `feature/sx-migration`
- migration baseline tag: `sandbox-sx-ready-t51`
- baseline commit: `1590836e473ad95f1941e98e7108d409f48cdeb9`
- pre-freeze working commit: `d0496c864532c7f6348de2a5611568cdbb214b40`
- final code commit: `cdb804247615a6a68a2de7a10084ee98d24748ea`
- final code tree: `a4c771b3cc9b06c1fc6831445fccb011210224fa`

## Migration architecture

The old SX-facing business flow now uses `SxSandboxAdapter` and the `sandbox-sdk` contract. Identity, package, instance, catalog, lifecycle, operation result, and compatibility decisions are explicit objects. The application UI and debug command path go through the package/session client boundary instead of a legacy SX hook mode.

Compatibility patches have a generic registry, explicit enable/disable state, and default-off behavior. No production module contains `com.sx`, `BlackBoxCore`, or `xposed_init` references; the DingTalk-shaped self-test fixture is isolated to the SDK test harness and is disabled by default.

The migration inventory, mapping, and hook audit are in:

- `docs/sx-migration/SX_LEGACY_FEATURE_INVENTORY.md`
- `docs/sx-migration/SX_TO_SANDBOX_MAPPING.md`
- `docs/sx-migration/SX_LEGACY_HOOK_AUDIT.md`

Non-business legacy hooks are intentionally deferred unless they are part of the Sandbox contract; no compatibility claim is made for an unimplemented legacy hook.

## Generic fixes delivered

- manifest component aggregation and duplicate component merging with conflict checks;
- activity-alias class preservation and intent-filter priority bounds;
- guest multidex class loading through `PathClassLoader` with controlled framework/host fallback;
- main-thread guest application preparation and safe `attachBaseContext` dispatch;
- bounded compressed `VirtualPackageStateSnapshot` transport;
- consistent 1024 data-rule caps;
- bounded transport error text;
- activity field bridge with generic guest metadata projection and type-mismatch handling;
- caller-task propagation, guest activity instrumentation, and host trampoline task flags;
- explicit native guest trust requirement for the debug acceptance path;
- package-data clearing and instance lifecycle controls exposed through the contract/UI.

## Acceptance matrix

| Stage | Required gate | Result | Evidence / reason |
|---|---|---|---|
| A | Simple fixture, two virtual users, lifecycle and isolation | PASS on preserved 20/20 run plus component/lifecycle suites; final fixture smoke passes | `D:\controlled-android-sandbox-evidence\T52-20260811-commit\simple-app\` |
| B | Quark import, prepare, launch, 10 starts, 5-minute stability | PARTIAL PASS / BLOCKED | SX formal prepare/launch and 335-second warm thin-core stability pass; the pristine cold-start and full 10-start gate remain unclaimed after generic service churn during rapid force-stop cycling. Primary evidence: `D:\controlled-android-sandbox-evidence\T52-20260811-final\quark\sx-thin-core-formal-20260811\` |
| C-prep | DingTalk static inventory, import, prepare, Guest READY | PASS | `D:\controlled-android-sandbox-evidence\T52-20260811-commit\dingtalk-prep\t52-final-dingtalk-prepare-result.json` |
| C-launch | DingTalk Activity create/resume, login/home, relaunch, 10 starts, 5-minute stability | FAIL / BLOCKED | Guest exits after `checkExportedActivityStartup` with `System.exit(0)` and recovers/restarts; stable home not reached |

The DingTalk launch result file is a request-layer PASS (`LAUNCH_REQUESTED`), not a Stage C acceptance PASS. The final report deliberately keeps that distinction.

## Security and identity review

The accepted native path requires an explicit trusted guest record. Ordinary packages do not receive that trust implicitly. Guest package name, virtual user, virtual UID, data root, session, generation, process slot, and package revision are broker/catalog/session-derived. Host package identity, host UID, and host data root are not reused as guest identity.

The generic compatibility registry defaults to disabled. No host identity, package manager, security check, or DingTalk-specific anti-check was bypassed. The remaining DingTalk behavior is recorded as an app/ROM-private runtime incompatibility rather than hidden with a package-specific branch.

## Build and review evidence

Final verification command:

`gradlew.bat check :app:assembleDebug :sandbox-companion32:assembleDebug`

Result: `BUILD SUCCESSFUL`; `sandbox-domain` self-test PASS; `sandbox-sdk` identity/compatibility self-test PASS; all lint and native build tasks passed. `git diff --check` passed, and static audits found no legacy-hook or temporary-diagnostic residue in product modules.

Known review status:

- P0: none known in the migrated code path.
- P1: none known in the migrated code path.
- P2: Quark pristine cold-start/10-start evidence is still incomplete; DingTalk startup exit remains an app-specific acceptance blocker and requires a legitimate generic runtime solution.

## Evidence, backup, and recovery

Evidence index: `docs/sx-migration/T52-EVIDENCE-INDEX.md`.

External evidence roots: `D:\controlled-android-sandbox-evidence\T52-20260811-commit\` (baseline) and `D:\controlled-android-sandbox-evidence\T52-20260811-final\` (fresh SX runtime evidence).

The `backup` directory contains the final branch bundle, source ZIP, restore script, and SHA-256 manifest. The bundle was verified and cloned into a fresh restore-check directory; the restored commit and tree are recorded in the final verification output.

## Final disposition

T52 is not complete because the requested three-level acceptance is not complete. Stage A is usable and Quark can now start normally on SX after its isolated thin core has been generated, but Quark's pristine/10-start gate and DingTalk Stage C must pass their full gates before the first line can be changed to `T52 RESULT: PASS`.
