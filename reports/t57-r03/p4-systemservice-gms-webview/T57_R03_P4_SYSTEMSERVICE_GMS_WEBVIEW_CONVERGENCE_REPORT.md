# T57-R03-P4 — SystemService Semantic Convergence + GMS / WebView / Account

## Result

`PASS WITH DEFERRED`

This phase closes the shared identity and ownership boundaries that can be proven by the
repository's static compiler and host self-tests. It does not claim full Android framework
parity, a real Google Play services runtime, OEM behavior, or device-level API evidence.

## Baseline

| Field | Value |
|---|---|
| Task | `T57-R03-P4-SYSTEMSERVICE-GMS-WEBVIEW-CONVERGENCE` |
| Branch | `feature/t57-r03-va-pro-capability-campaign` |
| START_HEAD | `671e5433a82a937a156b10ee4a11e5a00cce7cd4` |
| START_TREE | `30dff31e6e659c2b6fbec3c1d9e4fb18fbea9b47` |
| Implementation FINAL_HEAD | `fbf34e4d10f890f1cb3d958da32b4ed996e62efc` |
| Implementation FINAL_TREE | `08d4e98b902a8b3bce3687e6b9efe99219ea7568` |
| Report | `reports/t57-r03/p4-systemservice-gms-webview/T57_R03_P4_SYSTEMSERVICE_GMS_WEBVIEW_CONVERGENCE_REPORT.md` |

`Implementation FINAL_HEAD/TREE` identify the source/test implementation commit immediately
before this immutable evidence report commit. The final handoff also records the report commit
and actual final tree.

## Root causes

1. System-service interception had a shared Binder substrate, but semantic identity transforms
   were still distributed across service-specific paths. A ServiceManager hook therefore did not
   prove ownership, callback projection, or lifecycle correctness.
2. AppOps and newer `AttributionSource` shapes could carry package/UID identity through nested
   objects without one common Guest-to-Host and Host-to-Guest boundary.
3. Account state covered the basic virtual store but not the complete visibility, token callback,
   listener, remote-authority, and explicit-authenticator-deferred contract.
4. GMS availability was previously too close to a profile boolean. Package visibility and the
   presence of both GMS/GSF were not a sufficient prerequisite for installing a broker boundary.
5. WebView provider projection and renderer ownership existed, but path-bearing provider fields
   and cookie/web-storage/file-chooser ownership needed an explicit Guest storage boundary.

## Architecture changes

The common path is now:

```text
Guest API
  -> GuestIdentity
  -> SystemServiceSemanticAdapter
  -> AttributionSourceChain + identity rewrite
  -> Binder transaction / existing service adapter
  -> Host framework service
  -> callback / returned Binder boundary and death fence
  -> result + AttributionSource projection
  -> Guest API
```

Implemented changes:

- Added `SystemServiceSemanticContract`, `SystemServiceSemanticCatalog`, and
  `SystemServiceSemanticAdapter`. The catalog records ownership, identity transformation,
  callback path, lifecycle, and status; presence of a hook is explicitly not `CLOSED` evidence.
- Integrated the adapter into the delegated `SystemServiceInvocationHandler` path while
  retaining the existing `BinderInterceptionFoundation` callback/returned-object and death-fence
  ownership.
- Added reflection-only `AttributionSourceChain` support for nested state/next/array forms. It
  rewrites Guest package/UID to Host transport identity, restores mutable outbound arguments, and
  projects returned Host identity back to the Guest. Attribution tags remain opaque.
- Added AppOps Host-attribution rejection before outbound marshalling and retained the
  package-owned, fail-closed permission/AppOps policy paths. Permission mutations cannot be sent
  to the Host permission table.
- Added Account visibility to the durable virtual store and appended the AIDL methods after all
  existing transaction IDs. Local and remote authorities now support query, mutation,
  persistence, token invalidation, and listener notification.
- Added `AccountAuthenticatorBoundary`: Guest-owned token completion is returned through the
  callback; missing tokens and real authenticator operations have explicit error/deferred paths.
- Added `GmsCompatibilityBoundary`: GMS/GSF package visibility and Guest identity are checked
  before broker installation. Missing real runtime is reported as `DEFERRED_GMS_RUNTIME`; no
  full Play-services success is fabricated.
- Added Guest WebView cookie/web-storage/cache/file-chooser roots, provider data-path projection,
  host-path redaction, renderer/profile teardown, and bounded path/value checks in
  `WebViewStorageBoundary`.

P1 Binder/caller/returned-Binder/callback/death behavior, P2 package/split/LoadedApk/ABI
behavior, P3 native/procfs/FD/process identity behavior, and Activity FIX03 were not redesigned.

## SystemService capability matrix

The current machine-readable matrices are
[`T57_R03_BINDER_INTERCEPTION_MATRIX.yaml`](../../../docs/system/T57_R03_BINDER_INTERCEPTION_MATRIX.yaml)
and [`T57_R03_SYSTEM_SERVICE_STATE_MATRIX.yaml`](../../../docs/system/T57_R03_SYSTEM_SERVICE_STATE_MATRIX.yaml).
All rows below are semantic status, not hook-existence status.

| Service/domain | Status | Ownership and identity | Callback/lifecycle result |
|---|---|---|---|
| ActivityManager | PARTIAL | Guest task/activity ledger; package, UID, Binder session | Projected task/activity objects; ledger/death fence |
| PackageManager | PARTIAL | Virtual package universe; caller identity and visibility graph | ApplicationInfo/PackageInfo/component projection; revision/loader lifecycle |
| AppOps | PARTIAL | Guest policy; package, UID, `opPackageName`, AttributionSource | Mode/SyncNotedAppOp projection; mutation path remains package-owned |
| Permission | PARTIAL | Package owner and virtual UID | Guest check result; mutations fail closed to package authority |
| DevicePolicy | PARTIAL | Virtual package/user policy profile | Policy object projection; session/policy lease |
| Notification | PARTIAL | Guest ID/tag/channel namespace | Callback/id rebinding and observer fence; P5 depth deferred |
| Alarm | PARTIAL | Creator package/UID and PendingIntent token | Listener/PendingIntent association; generation cancellation |
| Connectivity | PARTIAL | Guest network profile and callback owner | NetworkCapabilities/LinkProperties projection; callback fence |
| Wifi | PARTIAL | Virtual device/package identity | Scan/result visibility; scoped scan callback |
| Telephony | PARTIAL | Virtual device/subscriber identity | Slot/callback projection; Binder fence |
| Location | PARTIAL | Provider identity and capability policy | Callback projection; capability lease ownership |
| Sensor | PARTIAL | Virtual device identity | Listener/event projection; listener lease |
| Camera | PARTIAL | Camera permission and virtual UID | Callback Binder boundary; capture lease teardown |
| Media | PARTIAL | Package/UID/media-session owner | Codec/session callback projection; session/death fence |
| Input | PARTIAL | Window token and process identity | Input device/event projection; window close fence |
| Accessibility | PARTIAL | Virtual package/user | Service identity projection; binding lifecycle |
| Autofill | PARTIAL | Virtual package/user | Fill callback projection; session close fence |
| Storage | PARTIAL | Package/user data root | Path/quota projection; revision-aware cleanup |
| Account | PARTIAL | Guest account authority and visibility | Account/authenticator response, token, listener, and remote-store paths |
| GMS | DEFERRED | Allowlisted GMS/GSF visibility and Guest UID only | Basic boundary can be ready; real broker/runtime APIs deferred |
| WebView | PARTIAL | Allowlisted provider and Guest data root | Provider/renderer/callback projection; suffix/profile teardown |

### Identity/permission domain

- `packageName`, virtual UID, and `opPackageName` are transformed through the common semantic
  adapter; Host package attribution is rejected when supplied to AppOps.
- `AttributionSource` chains are traversed without hardcoding a platform class, preserving
  API-level field shape differences while retaining Guest identity in the projection.
- Runtime permission checks use the Guest policy and virtual package owner. Mutation attempts are
  not delegated to the Host permission owner.
- AppOps operation results are projected to Guest policy modes, including structured
  `SyncNotedAppOp` construction where the framework signature requires it.

### Network/device domain

Connectivity, Wifi, Telephony, VPN ownership, Location, and Sensor remain `PARTIAL`. Their matrix
rows define the intended virtual identity, callback owner, and lifecycle fence. No broad fake data
claim is made: device-provider behavior and API-specific callback signatures still require
targeted device smoke.

### Notification/alarm/location/sensor

The existing virtual stores continue to own channel, PendingIntent creator, alarm, callback, and
listener state. Account-style listener dispatch and generation fences are now explicit in the
shared state path. Notification depth beyond the current basic surface remains deferred to P5.

### Camera/media/input/accessibility/autofill

The common adapter documents and protects identity ownership for these domains, while the existing
typed capability/service adapters own returned Binder and callback lifetime. The status remains
`PARTIAL` until API32/35/36 device signatures and provider behavior are exercised.

## Account / Identity result

`AccountManager` behavior is Guest-scope-owned for the implemented surface:

- create/query/remove, password, token set/peek/invalidate, visibility query/set, and listener
  changes are stored per virtual scope;
- the remote Package Service authority carries visibility through appended AIDL methods without
  changing prior transaction IDs;
- persistence/reload self-test proves visibility and token state survive authority recreation;
- `getAuthToken` completes with a Guest token and account metadata only through the supplied
  callback; an absent token returns an explicit `VIRTUAL_ACCOUNT_TOKEN_MISSING` error;
- `addAccount`, `confirmCredentials`, and `hasFeatures` paths requiring a real authenticator
  return `DEFERRED_ACCOUNT_AUTHENTICATOR` rather than fabricating success;
- no Host AccountManager query is used by the virtual account state path, so Guest account
  visibility cannot enumerate Host accounts.

Actual authenticator service binding/session parity remains `PARTIAL/DEFERRED`.

## GMS compatibility boundary

The scope is the basic boundary, not Google Play implementation.

- GMS (`com.google.android.gms`) and GSF (`com.google.android.gsf`) must be visible in the Guest
  package universe before the basic boundary is ready.
- Broker installation is gated by `GmsCompatibilityBoundary` and Guest identity; Host-mode,
  blocked, missing-package, and profile-disabled cases do not install the broker.
- Basic package/identity checks are covered by the compatibility self-test.
- A real GMS runtime, broker transaction corpus, Play account/token service, and arbitrary
  Play-services success are not present. Those paths report `DEFERRED_GMS_RUNTIME`.

## WebView result

- Provider selection uses the existing virtual WebView profile and provider contract; projected
  `ApplicationInfo` data, device-protected, and credential-protected paths are Guest paths.
- Installed provider metadata is redacted so Host WebView data paths are not returned through the
  compatibility PackageInfo surface.
- Each Guest/user/process profile has a suffix, Guest root, cache, database, service-worker,
  cookie, web-storage, and file-chooser namespace.
- `WebViewStorageBoundary` rejects paths outside the canonical Guest root and bounds cookie and
  web-storage keys/values. It never resolves a Host WebView data directory.
- Renderer ownership and teardown remain generation/profile-scoped; shutdown closes renderer
  state and the Guest storage boundary.
- Real Chromium renderer execution, CookieManager/database compatibility, and Android file
  chooser UI behavior are not device-verified here and remain `PARTIAL`.

## VA / NBB comparison

The reference baseline is retained under `ref/`; the current comparison boundary is summarized in
[`docs/VA_NBB_REFERENCE_BASELINE.md`](../../../docs/VA_NBB_REFERENCE_BASELINE.md).

The VA references include `VActivityManagerService`, `VPackageManagerService`,
`VAccountManagerService`, ServiceManager mirrors, and WebView/provider helpers. The NBB references
include `BinderHook`, `BPackageManagerService`, `BAccountManagerService`, WebView proxies, and
GMS/account helpers. VA/NBB remain stronger references for accumulated Android transaction
signatures, authenticator sessions, Chromium/GMS/OEM integrations, and application history.

CAS P4 is stronger in explicit ownership, revision/generation/death fencing, bounded durable
state, fail-closed identity projection, and separation of source evidence from device evidence.
Neither reference converts the presence of a hook into `CLOSED` parity.

## VA PRO gap mapping

### CLOSED (scope-limited guarantees)

- Shared delegated Binder path now has one semantic ownership contract and Guest/Host identity
  transform with mutable-argument restoration.
- Guest account state and WebView storage are scope-owned; Host Account/WebView data directories
  are not used as Guest projections.
- GMS broker installation and token/authentication paths fail closed when the required basic
  boundary is absent; no full-GMS success is fabricated.
- Existing P1/P2/P3 and Activity FIX03 self-test baselines show no new regression.

### PARTIAL

- All SystemService rows in the matrix marked `PARTIAL`, including ActivityManager, PackageManager,
  AppOps, Permission, network/device, callback-heavy media/input, and Storage.
- Account authenticator service binding/session parity and all Android AccountManager callback
  signatures beyond the covered callback boundary.
- WebView Chromium/provider/database behavior beyond Guest path and lifecycle ownership.
- GMS package/binder boundary after allowlisting, but not the real runtime.

### DEFERRED

- `DEFERRED_GMS_RUNTIME` and full Play services/account/broker behavior.
- API32 MuMu/RD, API35 `T57_R03_API35_x86_64`, and API36 `T57_R03_API36_x86_64` targeted device
  smoke; no device runner/evidence was available in this workspace.
- API37, complete commercial-App parity, OEM behavior, and P5 notification depth.

### OUT_OF_SCOPE

- OEM vendor adaptation, XH-specific patches, package-name hardcodes, P1/P2/P3 redesign, full
  Google Play implementation, and the final large commercial-App parity campaign.

## Validation and evidence

### Static compile

Command:

```text
python tools/static_android_compile.py
```

Result: `PASS`. Existing warnings are compiler warnings in the pre-existing stub/test surface;
there were no errors. The new SystemService semantic, account, GMS, and WebView self-tests pass.

### Local capability audit

Command:

```text
python tools/capability/run_local_capability_audit.py --all
```

Raw audit output was generated locally and is not retained in the source tree.

Result: 42 total; 29 `PASS`, 13 pre-registered `KNOWN_ISSUE`, 0 `EXPECTED_WARNING`, 0
`NEW_REGRESSION`, 13 diagnostic `FAIL` entries all classified as those existing known issues.
The architecture-quality gate is `PASS`; the overall diagnostic process exits 1 because the
known issues remain, as documented by the audit policy.

Known-issue IDs reported by the audit are `KI-R03-020`, `KI-M10-001`, `KI-M10-002`, `KI-R03-021`,
`KI-R03-022`, `KI-R03-027`, `KI-R03-023`, `KI-R03-024`, `KI-R03-025`, `KI-T57-008`,
`KI-R03-026`, `KI-R03-029`, `KI-M10-006`, `KI-M10-005`, and `KI-R03-028`.

### Diff check

Command:

```text
git diff --check
```

Result: `PASS` (the generated source-closure snapshot may report the repository's existing
CRLF/LF normalization warning; no whitespace error was found).

## API result

| Target | Result |
|---|---|
| API32 MuMu / RD | Deferred device smoke; source/static self-tests pass |
| API35 `T57_R03_API35_x86_64` | Deferred device smoke; source/static self-tests pass |
| API36 `T57_R03_API36_x86_64` | Deferred device smoke; source/static self-tests pass |
| API37 | `DEFERRED_API37` |

## Production blockers and deferred issues

No new source-level identity leak, callback ownership violation, framework lifecycle failure,
crash, or ANR was found by the available static/self-test evidence. Device evidence and a real
GMS runtime remain release prerequisites for dependent applications, but they are explicitly
non-blocking for this phase under the task rules.

Deferred issues:

- device runner/evidence for API32/35/36;
- API37;
- real GMS runtime and authenticator session parity;
- full WebView/Chromium CookieManager, database, renderer, and file chooser behavior;
- API-specific Connectivity/Wifi/Telephony/Location/Sensor callback corpus;
- OEM/XH behavior and final large commercial-App parity testing;
- P5 Notification depth.

## Commits

- `fbf34e4d10f890f1cb3d958da32b4ed996e62efc` — `feat(systemservice): converge framework service identity`
- This report, the coverage matrix, VA/NBB comparison, and refreshed source-closure snapshot are
  committed as the final evidence wrapper after the implementation commit; its hash is included
  in the final handoff receipt because the report cannot self-reference its own commit object.

## Final receipt

```text
RESULT: PASS WITH DEFERRED
TASK: T57-R03-P4-SYSTEMSERVICE-GMS-WEBVIEW-CONVERGENCE

START_HEAD: 671e5433a82a937a156b10ee4a11e5a00cce7cd4
START_TREE: 30dff31e6e659c2b6fbec3c1d9e4fb18fbea9b47

FINAL_HEAD: fbf34e4d10f890f1cb3d958da32b4ed996e62efc (implementation; report wrapper follows)
FINAL_TREE: 08d4e98b902a8b3bce3687e6b9efe99219ea7568 (implementation)

ROOT_CAUSES: scattered service semantics; incomplete AttributionSource/AppOps identity boundary; account visibility/token/authenticator gaps; GMS profile/visibility conflation; WebView path/storage projection gaps

SYSTEMSERVICE_IDENTITY: PARTIAL — shared semantic adapter and ownership catalog; service-specific completion remains matrix-scoped
APP_OPS: PARTIAL — Guest policy, opPackageName/UID checks, Host attribution rejection, result projection
PERMISSION: PARTIAL — Guest runtime check projection; mutations remain package-authority-owned
ATTRIBUTION_SOURCE: PARTIAL — API-shape-independent chain rewrite/restore/projection; device corpus deferred

NETWORK: PARTIAL
WIFI: PARTIAL
TELEPHONY: PARTIAL
LOCATION: PARTIAL
SENSOR: PARTIAL

CAMERA: PARTIAL
MEDIA: PARTIAL
INPUT: PARTIAL
ACCESSIBILITY: PARTIAL
AUTOFILL: PARTIAL

ACCOUNT: PARTIAL — durable Guest account/token/visibility/listener boundary; real authenticator binding deferred
GMS_BOUNDARY: DEFERRED_GMS_RUNTIME unless visible GMS+GSF basic boundary is present; no fabricated full GMS success
WEBVIEW: PARTIAL — Guest provider paths, profile storage, renderer ownership, and host-path redaction

P1_REGRESSION: 0
P2_REGRESSION: 0
P3_REGRESSION: 0

API32: DEFERRED_DEVICE_EVIDENCE
API35: DEFERRED_DEVICE_EVIDENCE
API36: DEFERRED_DEVICE_EVIDENCE
API37: DEFERRED_API37

VA_REFERENCE: ref/ upstream baseline, summarized by docs/VA_NBB_REFERENCE_BASELINE.md
NBB_REFERENCE: ref/ upstream baseline, summarized by docs/VA_NBB_REFERENCE_BASELINE.md

VA_PRO_GAP_CLOSED: shared semantic identity contract; Guest account/WebView negative boundaries; fail-closed GMS gate; P1/P2/P3 regression result
VA_PRO_GAP_PARTIAL: SystemService matrix; Account authenticator sessions; WebView Chromium behavior; GMS basic package/Binder boundary
VA_PRO_GAP_DEFERRED: real GMS runtime; API32/35/36 device smoke; API37; final commercial-App parity

PRODUCTION_BLOCKERS: none newly identified by available source/self-test evidence; device/GMS evidence remains release prerequisite
DEFERRED_ISSUES: runner/evidence; API37; real GMS/authenticator runtime; complete WebView; API-specific device callbacks; OEM/XH; final parity; P5 Notification depth

STATIC_COMPILE: PASS
LOCAL_AUDIT: 29 PASS / 13 KNOWN_ISSUE / 0 NEW_REGRESSION; diagnostic exit 1 only for known issues
NEW_REGRESSION: 0
GIT_DIFF_CHECK: PASS

COMMITS: fbf34e4d10f890f1cb3d958da32b4ed996e62efc; final evidence wrapper commit reported by handoff

REPORT: D:\github\controlled-android-sandbox\reports\t57-r03\p4-systemservice-gms-webview\T57_R03_P4_SYSTEMSERVICE_GMS_WEBVIEW_CONVERGENCE_REPORT.md

GIT_STATUS: clean at final handoff (verified after report commit)
NEXT: WAIT_FOR_NEXT_TASK
```
