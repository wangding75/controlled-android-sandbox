T52 RESULT: BLOCKED

# T52 final report — SX migration to Controlled Android Sandbox

## Executive result

The SX business surface has been migrated behind the Controlled Android Sandbox SDK/adapter boundary and the generic runtime hardening is implemented and buildable. Stage A evidence passes. Quark Stage B and DingTalk Stage C do not meet the requested acceptance gates, so the overall T52 result is **BLOCKED**, not PASS.

The blockers are runtime compatibility failures, not missing APKs, missing authentication, or a deliberately skipped test:

- Quark final trusted prepare ends with `GUEST_PREPARE_MAIN_THREAD_TIMEOUT`.
- DingTalk imports and prepares, and the launch request is accepted, but the guest reaches `checkExportedActivityStartup`, calls `System.exit(0)`, disconnects, and does not provide a stable login/home surface.

## Baseline and source state

- branch: `feature/sx-migration`
- migration baseline tag: `sandbox-sx-ready-t51`
- baseline commit: `fcaed533405b13465114d6f67fed5e3b5a066615`
- pre-T52 working commit: `1590836e473ad95f1941e98e7108d409f48cdeb9`
- final code commit: `052374d8a65d3e8777cc9e97cae1ac9b24b81e11`
- final code tree: `d779a266636b067c8a91397454e18b3b5cff4b73`

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
| B | Quark import, prepare, launch, 10 starts, 5-minute stability | FAIL / BLOCKED | Final prepare path times out in `GUEST_PREPARE_MAIN_THREAD_TIMEOUT`; no honest stable-launch PASS |
| C-prep | DingTalk static inventory, import, prepare, Guest READY | PASS | `D:\controlled-android-sandbox-evidence\T52-20260811-commit\dingtalk-prep\t52-final-dingtalk-prepare-result.json` |
| C-launch | DingTalk Activity create/resume, login/home, relaunch, 10 starts, 5-minute stability | FAIL / BLOCKED | Guest exits after `checkExportedActivityStartup` with `System.exit(0)` and recovers/restarts; stable home not reached |

The DingTalk launch result file is a request-layer PASS (`LAUNCH_REQUESTED`), not a Stage C acceptance PASS. The final report deliberately keeps that distinction.

## Security and identity review

The accepted native path requires an explicit trusted guest record. Ordinary packages do not receive that trust implicitly. Guest package name, virtual user, virtual UID, data root, session, generation, process slot, and package revision are broker/catalog/session-derived. Host package identity, host UID, and host data root are not reused as guest identity.

The generic compatibility registry defaults to disabled. No host identity, package manager, security check, or DingTalk-specific anti-check was bypassed. The remaining DingTalk behavior is recorded as an app/ROM-private runtime incompatibility rather than hidden with a package-specific branch.

## Build and review evidence

Final verification command:

`gradlew.bat :sandbox-domain:selfTest :sandbox-sdk:selfTest :sandbox-runtime:compileDebugJavaWithJavac :app:assembleDebug`

Result: `BUILD SUCCESSFUL`; `sandbox-domain` self-test PASS; `sandbox-sdk` identity/compatibility self-test PASS. `git diff --check` passed, and static audits found no legacy-hook or temporary-diagnostic residue in product modules.

Known review status:

- P0: none known in the migrated code path.
- P1: none known in the migrated code path.
- P2: Quark prepare timeout and DingTalk startup exit remain acceptance blockers and require further generic runtime investigation.

## Evidence, backup, and recovery

Evidence index: `docs/sx-migration/T52-EVIDENCE-INDEX.md`.

External evidence root: `D:\controlled-android-sandbox-evidence\T52-20260811-commit\`.

The `backup` directory contains the final branch bundle, source ZIP, restore script, and SHA-256 manifest. The bundle was verified and cloned into a fresh restore-check directory; the restored commit and tree are recorded in the final verification output.

## Final disposition

T52 is not complete because the requested three-level acceptance is not complete. Stage A is usable and the migration/runtime work is committed for continuation, but Quark Stage B and DingTalk Stage C must pass their full gates before the first line can be changed to `T52 RESULT: PASS`.
