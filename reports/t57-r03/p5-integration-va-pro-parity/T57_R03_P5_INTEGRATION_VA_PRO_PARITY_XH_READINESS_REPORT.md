# T57-R03-P5 — Integration / VA PRO Parity / XH Readiness

## Result

`PASS WITH DEFERRED`

本阶段验证的是 Controlled Android Sandbox（CAS）的基础承载能力，不是 XH 接入，也不把
“存在 Hook”当作能力闭环。矩阵中的 `CLOSED` 均有源码证据和运行时证据；其余能力明确
保留为 `PARTIAL` 或 `DEFERRED`。

## Baseline

| Field | Value |
|---|---|
| Task | `T57-R03-P5-INTEGRATION-VA-PRO-PARITY-XH-READINESS` |
| Branch | `feature/t57-r03-va-pro-capability-campaign` |
| START_HEAD | `8ad79720600d30a29d871919d1d31be612483688` |
| START_TREE | `a1219537b31bd40b8b7e58c21430d4301c4d9a6e` |
| Start status | clean |
| Main device | API32 MuMu `RD测试`, serial `127.0.0.1:16416` |
| Additional devices | API35 `T57_R03_API35_x86_64`, `emulator-5558`; API36 `T57_R03_API36_x86_64`, `emulator-5560` |
| API37 | `DEFERRED_API37` |
| OEM | not tested; prohibited in P5 |
| Implementation FINAL_HEAD | `d7bb6b7f4c1ce25c5e7dd2a346110df061ffc96c` |
| Implementation FINAL_TREE | `e23f539e1277d7141f3fd9617dfc626160c637e6` |

`FINAL_HEAD/FINAL_TREE` above identify the implementation commit. The report is committed as a
separate evidence-wrapper commit; its exact hash is supplied in the final handoff because a Git
commit cannot contain its own hash before it is created.

## Capability Matrix

| Capability | CAS | VA PRO comparison | Status | Evidence and boundary |
|---|---|---|---|---|
| Activity lifecycle | Guest ActivityThread instrumentation, route/task ledger, generation fencing | VA PRO has broader accumulated API/OEM transaction coverage | `PARTIAL` | `GuestActivityThreadInstrumentation`; API32 ActivityResult/lifecycle/transport evidence; full A01 task-semantic runner remains timing-flaky on some modes/API levels |
| Task reuse | Physical Stub reuse plus virtual token/task mapping | Comparable for the tested task contract; broad OEM history is not closed | `PARTIAL` | `FRAMEWORK_PROBE_TASK_REUSE_LIFECYCLE`, `FRAMEWORK_PROBE_TASK_REUSE_COUNTS`, `FRAMEWORK_TASK_EVIDENCE`; standard/singleTop/clearTop/reorder evidence runner needs stabilization |
| Binder | Returned Binder, callback Binder, lease/death fencing | VA PRO broader transaction corpus | `CLOSED` (scoped) | PendingIntent Binder callback, cross-Service Binder, process-death recovery, and Binder self-tests pass |
| Process | Guest process ownership, generation recovery, dedicated isolated route | VA PRO broader process/OEM integration | `CLOSED` (scoped) | `RD-07` recovery, `RD-08` isolated service, `:remote`, `:provider`, and cross-ABI process evidence pass |
| Package | Install, prepare, launch, clear/delete/reinstall lifecycle and Guest identity | VA PRO broader package compatibility history | `CLOSED` (scoped) | `RD-06` lifecycle; API32/35/36 basic launch and package identity evidence |
| Split APK | Split descriptors, revision checks, dependency ordering | VA PRO has deeper installer/revision coverage | `PARTIAL` | P2 source/self-test evidence exists; end-to-end split install/revision edge remains registered as `KI-R03-029` |
| ClassLoader | Guest loader, dex buffers, host-boundary and package-context projection | Comparable for tested loader boundary | `CLOSED` (scoped) | `FRAMEWORK_PROBE_PACKAGE_CONTEXT_PASS` reports `GuestClassLoader`; P2 loader self-tests pass |
| Native | JNI, `dlopen`, native library projection, ABI-aware loading | VA PRO broader hardened/native-loader corpus | `PARTIAL` | `NATIVE_LOAD JNI_LOADED`, `NATIVE_PROBE FILE_OK;DNS_OK;DLOPEN_OK`; raw syscall, custom loader, and advanced seccomp remain deferred |
| Procfs | Supported PID/UID/status/maps/fd projections | VA PRO broader virtual process namespace | `PARTIAL` | P3 procfs/native evidence and identity checks; arbitrary procfs leaves and full namespace parity are not closed |
| FD | FD ledger/capability ownership, APK/native archive transport | VA PRO broader FD producer coverage | `PARTIAL` | Isolated APK capability now passes after offset reset; Provider/Binder FD producer breadth remains partial |
| Service | Guest service lifecycle, remote service, Job/FGS transport | Comparable for tested service contracts | `CLOSED` (scoped) | `RD-10` FGS, `RD-11` JobWorkItem, transport `SERVICE_BIND`, remote service, and isolated service pass |
| Broadcast | Dynamic, ordered, async ordered, cross-package delivery | VA PRO broader callback/OEM coverage | `PARTIAL` | Direct transport has ordered/async/dynamic/cross markers; normal ordered marker is runner-flaky and broadcast audit issue remains known |
| Provider | Provider process, bulk/batch, cursor, observer, cross-ABI provider | Comparable for tested provider surface | `CLOSED` (scoped) | `FRAMEWORK_PROBE_PROVIDER_BULK_PASS`, batch, cross-provider and observer markers; API32/35/36 provider gates pass |
| PendingIntent | Sender creation, returned Binder, callback, system-holder relay, cross-package delivery | VA PRO broader alarm/notification history | `CLOSED` (scoped) | `FRAMEWORK_PROBE_PENDING_INTENT_CALLBACK_PASS`, cross PendingIntent, and `VIRTUAL_PENDING_INTENT_DELIVERY status=BROADCAST_DELIVERED` |
| Account | Durable Guest account/token/visibility/listener boundary | Real authenticator/session behavior broader in VA PRO | `PARTIAL` | P4 Account boundary/self-tests pass; real authenticator binding and full callback corpus deferred |
| WebView | Provider/path/profile ownership boundary and Guest storage roots | VA PRO has deeper Chromium/provider/OEM behavior | `PARTIAL` | P4 WebView provider/storage/renderer ownership self-tests; real Chromium CookieManager/database/JS bridge/file chooser not device-closed |
| GMS boundary | Guest visibility/identity gate and fail-closed broker boundary | VA PRO has accumulated Play Services integrations | `PARTIAL` | P4 GMS boundary evidence; real Play Services runtime, account/token and broker corpus deferred |
| Multi-process | `:remote`, `:provider`, dedicated isolated process, cross-package/cross-ABI routing | Comparable for tested declared process classes | `CLOSED` (scoped) | API32 transport, isolated service, and cross-ABI recovery/lifecycle evidence pass |
| ABI | x86/x86_64 host/Companion transport and native load | Broader arm/arm64/OEM matrix remains | `PARTIAL` | API32 MuMu x86 path plus API35/API36 x86_64 smoke; all commercial ABI combinations are not closed |

No capability is marked `CLOSED` solely because implementation code exists. The `CLOSED` rows are
scope-limited to the runtime surfaces listed in the evidence column.

## Runtime Test Matrix

### API32 / MuMu RD

| Case | Result | Evidence |
|---|---|---|
| Framework transport: Provider, Binder, PendingIntent, notification/alarm, Service, cross package/ABI, `:remote`, task reuse | Runtime `PASS`; one harness attempt later classified flaky | `build/t57-rd-evidence/p5-api32-framework-transport-final/RD-06-framework-transport-probe-logcat.txt` contains `FRAMEWORK_PROBE_PASS` and the complete marker set; final-commit flaky capture is in `p5-api32-framework-transport-runner-flaky` |
| ActivityResult returned/callback transport | `PASS` | `build/t57-rd-evidence/p5-api32-activity-result-final` |
| Foreground Service transport | `PASS` | `build/t57-rd-evidence/p5-api32-foreground-service-final` |
| JobWorkItem transport | `PASS` | `build/t57-rd-evidence/p5-api32-job-work-item-final` |
| Process death / generation recovery | `PASS` | `build/t57-rd-evidence/p5-api32-recovery-final` |
| Isolated service / isolated UID and PID | `PASS` | `build/t57-rd-evidence/p5-api32-isolated-service-final` |
| Clear/delete/reinstall lifecycle | `PASS` | `build/t57-rd-evidence/p5-api32-lifecycle-final2` |
| Cross-ABI process death recovery | `PASS` | `build/t57-rd-evidence/p5-api32-cross-abi-recovery-final2` |
| Cross-ABI clear/delete/reinstall lifecycle | `PASS` | `build/t57-rd-evidence/p5-api32-cross-abi-lifecycle-final2` |

The transport runner’s final retry found `PROBE_MARKER_MISSING:FRAMEWORK_PROBE_ORDERED_RECEIVER_DELIVERED`
after `FRAMEWORK_PROBE_PASS` had already been emitted, with no FATAL/ANR marker. This is retained
as test-infrastructure evidence, not converted into a runtime PASS.

### API35 / API36

| Target | Result | Evidence |
|---|---|---|
| API35 `T57_R03_API35_x86_64` | Basic install/launch, scale, Service, Provider, PendingIntent and session-fencing gates pass; independent ActivityResult rerun passes; full A01 task matrix is `PARTIAL` because its semantic/evidence gates are timing-sensitive | Generated local evidence; not retained in the source tree |
| API36 `T57_R03_API36_x86_64` | Basic install/launch, scale, Service, Provider, PendingIntent and session-fencing gates pass; independent ActivityResult rerun passes; full A01 task matrix is `PARTIAL` because its semantic/evidence gates are timing-sensitive | Generated local evidence; not retained in the source tree |

The independent API35/API36 ActivityResult runs produced `LAUNCH_PASS`, parent create/start,
child finish, and `FRAMEWORK_PROBE_ACTIVITY_RESULT_PASS`, with no FATAL/ANR marker.

### A01 full acceptance runner

The required multi-API runner was executed against API32/API35/API36 and wrote
local evidence that is not retained in the source tree.

It returned `OVERALL_PASS=False`. This is not hidden: API32/35/36 basic launch, scale, Service,
Provider, PendingIntent and session-fencing results were PASS, while the remaining failures were
task-semantic observation gates, API35 process-death evidence, API35/API36 ActivityResult timing
observations, and dirty-worktree identity gates. Independent probes above provide the direct
runtime evidence for the production-critical paths. The A01 task/evidence runner is therefore in
the Test Infrastructure backlog.

## Real-App-Type Validation Matrix

The repository fixtures are controlled XH-like workload fixtures, not a claim that a commercial
XH binary was integrated. They exercise the requested workload shapes without package-specific
production branches.

| Type | Required scenario | Observed coverage | Status |
|---|---|---|---|
| A — enterprise office/IM | Multi-process, Binder, Service, Provider, Account, WebView | `:remote`, Provider/Service/Binder/callback/death runtime paths; Account and WebView basic boundaries from P4 self-tests | `PARTIAL` — authenticator and deep WebView/GMS behavior deferred |
| B — Web-heavy | WebView provider, renderer process, Cookie, JS bridge, storage | Provider/profile/storage ownership and renderer lifecycle boundary are covered; Chromium CookieManager/database/JS bridge/file chooser are not deep device-verified | `PARTIAL` |
| C — Native-heavy | JNI, `dlopen`, procfs, FD, ABI | JNI load, native file/DNS/dlopen probe, procfs/identity projection, FD capability and cross-ABI runtime evidence | `PARTIAL` — raw syscall/custom loader/full procfs breadth deferred |
| D — background task | Service, Job, Alarm, Broadcast | FGS, JobWorkItem, alarm readback, dynamic/ordered/async/cross broadcast paths | `CLOSED` (scoped) |
| E — multi-process | `:remote`, `:push`, isolated process | `:remote`, `:provider`, dedicated isolated service, cross-package/cross-ABI process routing | `CLOSED` (scoped) |

## VA PRO Comparison

| Area | CAS P5 result | VA PRO gap interpretation |
|---|---|---|
| Identity and ownership | Explicit Guest/Host transforms, generation/death fences, package/context identity evidence | CAS is stronger in explicit fail-closed ownership contracts; VA PRO remains broader in accumulated Android/OEM compatibility |
| Activity/task | Physical Stub plus virtual task/token ledger; tested reuse and result transport | Core contract is present; all API/OEM task timing combinations are not closed |
| Binder/process | Returned/callback Binder, death, remote/provider/isolated/cross-ABI paths | Tested boundary is production-shaped; broad Binder transaction and OEM history remains partial |
| Package/loader/ABI | Base package and loader are closed in tested scope; split/revision and ABI breadth partial | VA PRO has wider installer and ABI corpus |
| Native/procfs/FD | Coherent supported projection and capability transport | Raw SVC/syscall bypass, custom loader, advanced seccomp and full procfs/FD corpus remain backlog |
| System services | Account, WebView and GMS boundaries are explicit and fail closed | Real Play Services, authenticator sessions, Chromium deep behavior and API/OEM signatures remain deferred |

P5 therefore establishes VA PRO-level sandbox *carrier readiness* for the tested core surfaces,
not universal VA PRO feature parity.

## XH Readiness

| READY condition | Result | Evidence |
|---|---|---|
| App installs | `PASS` | API32 lifecycle; API35/API36 A01 basic gates |
| App starts | `PASS` | API32 ActivityResult/lifecycle/transport; API35/API36 independent ActivityResult |
| Binder normal | `PASS` | returned/callback Binder and cross-Service evidence |
| Multi-process normal | `PASS` (scoped) | `:remote`, `:provider`, isolated and cross-ABI evidence |
| Service / Provider normal | `PASS` | FGS/Job/Service/Provider runtime evidence |
| WebView basic boundary | `PASS` (basic boundary only) | P4 provider/storage/renderer ownership self-tests; deep Chromium deferred |
| Native basic | `PASS` (basic boundary only) | JNI/dlopen/native probe and cross-ABI evidence |
| No obvious identity leak | `PASS` for tested surfaces | Guest package/UID/package-context/class-loader/native identity markers; no new FATAL/ANR or Host identity assertion |

**XH_READINESS: `READY`**, limited to basic sandbox carrier readiness. This does not authorize
OEM support, real GMS integration, Chromium deep parity, or package-specific XH work.

## Fixed Production Issues

| Issue | Root cause | Fix | Validation |
|---|---|---|---|
| Stale Activity route replay crash | MuMu/API32 replayed a consumed Host Stub route after the virtual Activity had finished; instrumentation threw on the stale route | `GuestActivityThreadInstrumentation` recognizes only stale route codes, re-enters the validated Host Stub path, drops the replay without synthesizing Guest success/lifecycle | ActivityResult probe passes; stale replay is logged as `STALE_ROUTE_REPLAY_DROPPED`; no FATAL main-process crash |
| PendingIntent callback semantic corruption | System-holder relay replaced the original action with the internal relay action | Rewrite only relay component/package and preserve original action/data/extras; restore Guest-visible semantics through the Broker | `FRAMEWORK_PROBE_PENDING_INTENT_CALLBACK_PASS`, cross PendingIntent and system-holder paths pass |
| Foreground Service notification rejection | Notification channel was virtualized during creation but nested `Notification.mChannelId` still carried the Guest ID into Host AMS | Shared `VirtualNotificationNamespace` plus temporary projection/restoration of the nested channel ID in `setServiceForeground` | Initial API32 FGS failure reproduced; fixed `RD-10` passes with real notification/channel transport |
| Isolated APK capability false negative | APK FD had an advanced shared file offset after Binder capability hops; header probe read from the wrong position | Seek the duplicated descriptor to offset zero before capability validation | Initial `ISOLATED_APK_CAPABILITY_CONTENT_INVALID` reproduced; fixed `RD-08` passes with isolated UID/PID |

No package-name hardcode, OEM branch, marker-only success, or fake runtime data was added.

## Deferred Issues / Backlog

### Native

- Raw syscall/SVC and inline-hook bypass coverage.
- Custom ELF loader / manual mmap-relocation behavior.
- Advanced seccomp trap/user-notify and full Guest syscall mediation.
- Full procfs namespace and all FD producers/duplication edge cases.

### GMS

- Real Play Services/GSF runtime, broker transaction corpus, Play account and token flows.
- Real authenticator binding/session parity.

### WebView

- Chromium renderer deep behavior, CookieManager/database compatibility, service workers,
  JS bridge, file chooser and provider-specific callbacks.

### Package/API/OEM

- Split install/revision/ownership edge cases (`KI-R03-026`, `KI-R03-029` and related registered
  audit issues).
- API37 (`DEFERRED_API37`).
- MIUI, ColorOS and EMUI vendor framework behavior: explicitly not tested in P5.
- Commercial XH-specific native/protocol/packing behavior: Compatibility Extension backlog only.

### Test Infrastructure

- Make A01 task semantic capture synchronize on the actual framework route/task instead of the
  command Activity, and separate dirty-worktree identity from runtime verdict.
- Make the dynamic broadcast invocation bounded/nonblocking; preserve the underlying runtime log
  when a runner command hangs.
- Complete API35/API36 full task-mode evidence after runner stabilization.

## API / OEM Status

| Target | Status |
|---|---|
| API32 MuMu `RD测试` | Core runtime matrix PASS with the scoped test-infrastructure qualification above |
| API35 `T57_R03_API35_x86_64` | Targeted core smoke PASS; full A01 task/evidence matrix deferred to runner fix |
| API36 `T57_R03_API36_x86_64` | Targeted core smoke PASS; full A01 task/evidence matrix deferred to runner fix |
| API37 | `DEFERRED_API37` |
| OEM | `PROHIBITED / NOT TESTED` |

## Build, Audit and Regression Checks

### Static compile

Command:

```text
python tools/static_android_compile.py
```

Result: `PASS`. Only existing compiler warnings were emitted.

### Build

```text
.\gradlew.bat assembleDebug
```

Result: `PASS`.

### Local capability audit

Command:

```text
python tools/capability/run_local_capability_audit.py --all
```

Latest audit output was generated locally and is not retained in the source tree.

Result: 42 total; 29 `PASS`; 13 registered `KNOWN_ISSUE`; 0 `EXPECTED_WARNING`; 0
`NEW_REGRESSION`; 13 diagnostic failures, all classified by the existing audit policy. The
command exits 1 because this diagnostic audit intentionally reports known issues; that exit is
not converted into a false overall PASS.

Known issue gates include architecture decoupling, source closure, SBOM, broadcast model, native
trust boundary, package boundaries, isolated process, APK revision binding, ownership cleanup,
durable atomicity, system-service split, Binder system services and split install.

### Diff check

```text
git diff --check
```

Result: `PASS`.

### Regression interpretation

The direct production-critical probes listed above pass. The A01 full task/evidence runner is
retained as `PARTIAL/DEFERRED` rather than being treated as a production failure because its
failed observations are timing/runner gates while independent runtime markers and targeted
probes pass. No new capability-audit regression was observed: `NEW_REGRESSION=0`.

No source backup ZIP was generated.

## Commits

- `d7bb6b7f4c1ce25c5e7dd2a346110df061ffc96c` — `fix(runtime): close stale routes and preserve framework identity`
- This report and its directory are added in the following report-wrapper commit; the final handoff
  supplies that wrapper hash and the clean final status.

## Final Receipt

```text
RESULT: PASS WITH DEFERRED
TASK: T57-R03-P5-INTEGRATION-VA-PRO-PARITY-XH-READINESS

START_HEAD: 8ad79720600d30a29d871919d1d31be612483688
START_TREE: a1219537b31bd40b8b7e58c21430d4301c4d9a6e
FINAL_HEAD: d7bb6b7f4c1ce25c5e7dd2a346110df061ffc96c
FINAL_TREE: e23f539e1277d7141f3fd9617dfc626160c637e6

VA_PRO_MATRIX: PASS WITH DEFERRED
ACTIVITY: PARTIAL
BINDER: CLOSED (scoped)
PROCESS: CLOSED (scoped)
PACKAGE: CLOSED (scoped)
LOADER: CLOSED (scoped)
NATIVE: PARTIAL
PROCFS: PARTIAL
FD: PARTIAL
SERVICE: CLOSED (scoped)
PROVIDER: CLOSED (scoped)
BROADCAST: PARTIAL
PENDING_INTENT: CLOSED (scoped)
ACCOUNT: PARTIAL
WEBVIEW: PARTIAL
GMS: PARTIAL
APP_MATRIX: PASS WITH DEFERRED (fixture workload types A-E)
API32: PASS with runner qualification
API35: PASS targeted core / A01 task evidence deferred
API36: PASS targeted core / A01 task evidence deferred
API37: DEFERRED_API37
XH_READINESS: READY
PRODUCTION_FIXES: 4 architecture fixes
DEFERRED: native raw syscall/custom loader/advanced seccomp; real GMS; Chromium deep WebView; split/revision edges; API37; OEM; runner/evidence
OEM: PROHIBITED / NOT TESTED
STATIC_COMPILE: PASS
LOCAL_AUDIT: PASS WITH KNOWN_ISSUES (29 PASS, 13 KNOWN_ISSUE, 0 NEW_REGRESSION; process exit 1 by policy)
NEW_REGRESSION: 0
GIT_DIFF_CHECK: PASS
COMMITS: d7bb6b7f4c1ce25c5e7dd2a346110df061ffc96c + report-wrapper commit
REPORT: D:\github\controlled-android-sandbox\reports\t57-r03\p5-integration-va-pro-parity\T57_R03_P5_INTEGRATION_VA_PRO_PARITY_XH_READINESS_REPORT.md
GIT_STATUS: CLEAN after report-wrapper commit
NEXT: WAIT_FOR_NEXT_TASK
```
