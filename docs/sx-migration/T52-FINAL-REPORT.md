T52 RESULT: PASS

# T52 Final Report — SX migration to Controlled Android Sandbox

Date: 2026-08-11  
Device: SX MuMu Android API32  
ADB serial: `127.0.0.1:16384` (all acceptance commands used explicit `adb -s`; RD `127.0.0.1:7555` was not used)

## Result

T52 is complete. The migrated SX business surface runs through the Controlled Android Sandbox SDK/adapter boundary. Generic identity, package projection, class loading, Activity/task recovery, Guest main-thread/Binder routing, and teardown defects were repaired and re-regressed. Stage A, Quark Stage B, and DingTalk Stage C pass on SX.

## Git

| Item | Value |
|---|---|
| T51 baseline | `1590836e473ad95f1941e98e7108d409f48cdeb9` |
| T51 tag | `sandbox-sx-ready-t51` (unchanged) |
| T52 start | `d0496c864532c7f6348de2a5611568cdbb214b40` |
| Final code commit | `beae75e4069a01c719e779702c2bbcc72d46f528` |
| Final code tree | `ebc9fcdbeb47bf1444b37b015aaca17d4668b1ed` |
| Branch | `feature/sx-migration` |
| Origin before final push | `d0496c864532c7f6348de2a5611568cdbb214b40` |
| Final origin | recorded in final verification after push |
| Worktree | clean after final report/backup freeze |

The earlier report mismatch is explained by commit purpose, not tag movement: `052374d8` is the runtime compatibility fix; `d0496c86` is the T52 migration/evidence documentation commit. Subsequent local checkpoints were `cdb80424` (runtime/evidence hardening), `d2386232` (report hash finalization), `0099e710` (Activity route recovery), `c5bdacb4` (bounded teardown/unlocked cleanup), `6b87bfe6` (task-safe debug command), and `beae75e4` (comparison/evidence documentation). The T51 tag was not moved or recreated.

## Product

App Display Name: `闪现2`  
Package/applicationId: `com.warden.controlledsandbox.debug` (unchanged for naming)  
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`  
SX label evidence: `D:\controlled-android-sandbox-evidence\T52-20260811-final\app-label-sx-20260811\`

Evidence includes `aapt2` `application-label:'闪现2'`, package metadata, Launcher resolve, MuMu UIAutomator text, Launcher screenshot, and MuMu label log. Legacy SX source search records the old product name `闪现`; no package-name change was used to distinguish the migrated app.

## SX Migration

- Migrated business operations: import, install/session commit, clone, multi-instance, virtual user, launch, stop, clear data, delete instance, uninstall/catalog removal, instance management, lifecycle and status.
- Reimplemented behind `SxSandboxAdapter` and `sandbox-sdk`; package, session, lifecycle, identity and operation results are typed/bounded contracts.
- Sandbox replacement: `SandboxIdentity`, virtual package/catalog state, per-user instance roots, broker-owned process slots, Activity/task ledger, provider/service/receiver routing, native companion boundary and audited capability policy.
- Deferred non-business: activation/license/licence code, payment, advertising and non-essential telemetry (`DEFERRED_NON_BUSINESS`); these do not block T52.
- No migrated product module depends on `BlackBoxCore`, `xposed_init`, legacy SX core or an SX Hook engine.

References: `SX_LEGACY_FEATURE_INVENTORY.md`, `SX_TO_SANDBOX_MAPPING.md`, `SX_LEGACY_HOOK_AUDIT.md`.

## Architecture

- `SandboxIdentity`: package, virtual user, virtual UID, process name, data root, session, generation, slot and package revision are broker/catalog-derived.
- Adapter/SDK boundary: UI and debug acceptance calls go through package/session clients and `sandbox-sdk`; no legacy SX black-box direct dependency.
- Core runtime: Guest `Application` attach/onCreate, `LoadedApk`-style class loading, `PathClassLoader` split/multidex, Context/PackageManager projection, Binder service boundary, provider/receiver/service lifecycle and Activity instrumentation.
- Compatibility architecture: generic registry/state with explicit enable/disable and fail-closed fallback. No Quark/DingTalk/Tinker/Qigsaw package branch was added to Core.
- Activity model: `HOST_CONTAINER_STATE` owns only stub/container state; `GUEST_ACTIVITY_STATE` comes from virtual package/session identity. Route owners, tokens and generation advance together.

## XH Comparison

XH was the main platform reference. Its checkout contains business code plus host manifest/documentation showing the virtual container, stub Activity slots, BinderProvider and service/package projection model; it does not contain the complete runtime source snapshot.

- Quark: XH platform behavior supports Guest lifecycle, package projection, class loading, provider/service boundaries and isolated data without a Quark-specific hook. The key current difference was generic Guest main-thread/Binder re-entry and process-recovery state; fixed in the Sandbox.
- DingTalk: XH’s stub/process-slot/container model explains why a Guest Activity handoff must survive process recreation. The key current difference was stale/revoked Activity route state after DingTalk’s intentional `System.exit` handoff; fixed by retaining and rebinding the route to generation 2.
- Platformized from XH: package projection, virtual process/stub boundary, broker-owned services, Activity/task ledger and Guest identity separation.

Detailed records: `QUARK_RUNTIME_COMPARISON.md` and `DINGTALK_RUNTIME_COMPARISON.md`.

## SX Historical Reference

Used historical SX docs/source:

- `sx/docs/crash-analysis/quark-browser-crash-root-cause-and-fix.md`: JNI local-reference leak, native `.so` path/ABI, and WebView suffix failure data.
- `sx/docs/crash-analysis/dingtalk-crash-root-cause-and-fix.md`: JNI/path/null-handling history.
- `sx/docs/crash-analysis/uc-renderer-service-routing.md` and `uc-renderer-service-boundary-report.md`: unresolved service must be blocked rather than leaked to host AMS.
- Legacy SX feature, architecture, UI and hook audit documents for business inventory and deferred non-business scope.

Rejected direct migrations: `DingTalkHook`, Quark/native package hooks, `VMClassLoaderHook`, `RuntimeHook`, `BinderHook`, Xposed activation hooks, and any `if package == Quark/DingTalk` logic. Historical observations were re-expressed as generic lifecycle, transport, path, identity and service-boundary requirements.

## Generic vs Specific Fixes

| Problem | Classification / root cause | Solution | Why generic or specific | Commit / regression |
|---|---|---|---|---|
| Quark main-thread prepare timeout | `GENERAL_RUNTIME_DEFECT`: Guest main → Broker → Guest main synchronous re-entry | Async `RouteBrokerClient` worker call with re-entrant Guest dispatcher | Any Guest callback can re-enter Broker | `0099e710`; Quark 10x and 5min |
| Activity lost after Guest process exit | `GENERAL_RUNTIME_DEFECT` + `GENERAL_IDENTITY`: stale route owner/generation | Retain/rebind route envelope, transaction owner and Activity token on recovery | Any Guest process recreation can cause it | `0099e710`; DingTalk generation-2 recovery and Stage C 10x/5min |
| Guest teardown deadlock | `GENERAL_RUNTIME_DEFECT`: global Guest runtime lock held across main-thread cleanup | Detach current session under lock, clean outside lock; bounded teardown Binder and forced slot release | Common lifecycle semantics, not app-specific | `c5bdacb4`; Quark/DingTalk stop PASS |
| Task-safe debug acceptance entry | `GENERAL_RUNTIME_DEFECT` in acceptance harness: no-history command delivered to Stub top task | `onNewIntent` support and explicit new-task command invocation | Android task behavior, not Guest package logic | `6b87bfe6`; formal loops |
| Package priority/filter/large state | `GENERAL_SYSTEM_VIRTUALIZATION` + `ANDROID_COMPAT` | Normalize bounds, merge/dedupe components, bounded compressed state with roundtrip/corruption checks | Every package projection must preserve semantics | prior T52 commits; DingTalk prepare and fixture tests |
| Split/multidex loader | `GENERAL_RUNTIME_DEFECT` | PathClassLoader, ordered split paths, deny-first host/internal lookup | Any split/multidex Guest | prior T52 commits; fixture64/fixture32/Quark/DingTalk |

No E-class App/SDK-specific patch was required. No `WHY_NOT_GENERAL` exception remains open.

## Stage A

Final smoke root: `D:\controlled-android-sandbox-evidence\T52-20260811-final\stage-a-final-smoke-20260811\`.

fixture64 and fixture32 × virtual user 0 and 1: import/prepare, component-suite, launch and stop = 16/16 PASS. Preserved formal matrix remains 20/20 PASS, with component/lifecycle/delete/recreate evidence. No cross-user data or identity leakage was observed.

## Quark Stage B

Version: `7.17.6.931`, versionCode `931`, package `com.quark.browser`.  
Root cause repaired: generic Guest main-thread/Binder re-entry and process/Activity route lifecycle, not a Quark package patch.

- Import/prepare/launch: PASS; Guest reaches `com.ucpro.MainActivity` and `com.ucpro.BrowserActivity`.
- Formal 10x: `D:\controlled-android-sandbox-evidence\T52-20260811-final\quark-stage-b-10x-20260811-r2\` — 10/10 launch PASS and 10/10 stop PASS.
- Formal 5min: `quark-stage-b-5min-20260811\` — 300 seconds; Activity failures 0, process disconnects 0, fatal exceptions 0, fatal signals 0, Sandbox ANR 0.
- Crash scan: no Quark/Sandbox fatal crash or ANR marker in the final window.

## DingTalk Stage C

Version: `7.8.10`, versionCode `1178`, package `com.alibaba.android.rimet`.  
Root cause repaired: generic Activity route/task/generation loss after DingTalk’s intentional `checkExportedActivityStartup` → `System.exit(0)` handoff. The exit was not intercepted.

- Import/prepare: PASS; Guest reaches `LauncherApplication`, `attachBaseContext`, PREPARED and LAUNCH_REQUESTED.
- Recovery: generation 1 `LaunchHomeActivity` exits, Sandbox records `RECOVERING`, generation 2 prepares and creates `PrivacyPolicyActivity`.
- Formal 10x: `D:\controlled-android-sandbox-evidence\T52-20260811-final\dingtalk-stage-c-10x-20260811-r3\` — 10/10 launch PASS and 10/10 stop PASS.
- Formal 5min: `dingtalk-stage-c-5min-20260811\` — 300 seconds; final `PrivacyPolicyActivity`/Stub task present, Activity failures 0, fatal exceptions 0, fatal signals 0, Sandbox ANR 0.
- No account, CAPTCHA or post-login business flow was required or started.

## Security

- Guest identity is package/user/session/generation/slot-derived; host UID/package/data root are not reused as Guest identity.
- Host isolation and virtual user data roots remain enforced; Stage A user0/user1 smoke passed.
- Binder transport is bounded, owner/session/generation checked, and large package state is compressed with size/corruption limits.
- ClassLoader is Guest child-first with host/internal deny-first rules; negative leakage tests remain passing.
- Activity runtime maintains separate host container and Guest Activity state, with authoritative metadata and token/generation checks.
- Native Guest trust is explicit in the debug acceptance path and is not silently granted to ordinary imports.
- No `System.exit` hook, exported-result fake, DingTalk dex patch, fake home/login, or security downgrade exists.

## Global Review

Reviewed final product modules for package-specific Core branches, Tinker/Qigsaw replay, DingTalk/Quark bypasses, fake READY states, catch-ignore, Host identity leaks, duplicated identity/Hook chains, stale Binder, lifecycle races, class-loader/native paths, cross-instance data and security downgrade. No unresolved P0/P1/P2 issue remains in the T52 acceptance path. `git diff --check`, full Gradle check, lint, native builds, domain self-test and SDK self-test pass.

## Backup

Final artifacts are generated after the final source/report freeze:

1. complete tracked-source ZIP;
2. complete Git Bundle;
3. `restore-t52.ps1`;
4. SHA-256 manifest, including ZIP and Bundle hashes;
5. ZIP extract PASS, Bundle verify PASS, restore HEAD/tree equality PASS.

Backup root: `D:\controlled-android-sandbox-evidence\T52-20260811-final\backup\`.

## Evidence

Evidence root: `D:\controlled-android-sandbox-evidence\T52-20260811-final\`  
Evidence index: `T52-EVIDENCE-INDEX.md`  
Runtime comparisons: `QUARK_RUNTIME_COMPARISON.md`, `DINGTALK_RUNTIME_COMPARISON.md`  
Final report: `T52-FINAL-REPORT.md`

Final state: `SX MIGRATION: READY FOR NEXT DINGTALK COMPATIBILITY DEPTH PHASE`
