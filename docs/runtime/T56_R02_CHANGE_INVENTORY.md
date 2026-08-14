# T56-R02 Change Inventory

Audit of the dirty tree at R02 start. Code existing in the working tree is **not** accepted as correct.

Baseline: `bfa436f14044` on `feature/t56-product-convergence`.
Device: Xiaomi `25019PNF3C` / `xuanyuan` / HyperOS OS3.0 / Android 16 API36 / `192.168.137.186:39531`.
Raw diffs: `build/t56-r02-checkpoint/`.

Classifications: `GENERAL_IDENTITY` | `GENERAL_PACKAGE_VIRTUALIZATION` | `GENERAL_SYSTEM_VIRTUALIZATION` | `GENERAL_RUNTIME_DEFECT` | `ANDROID_COMPAT` | `TEST_INFRASTRUCTURE` | `APP_OR_SDK_SPECIFIC`.

Verdicts: **KEEP** (direction correct, needs tests), **ADJUST** (must change before commit), **REMOVE** (must not stay in Core).

---

## File inventory

### 1. `app/.../ApkImportManager.java`

| Field | Value |
| --- | --- |
| Issue | Split APKs rejected by host `getPackageArchiveInfo`; Clear Data failed on sealed read-only parents. |
| Symptom | `PackageManager rejected an APK artifact` for valid base+split sets. Clear hit `AccessDenied` on `dso_lock` / `lib-compressed`. |
| Root cause | Every artifact had to parse as standalone `PackageInfo`. `deleteTreeOrThrow` made children writable but not the parent. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` + `GENERAL_RUNTIME_DEFECT` |
| Current modification | Identify unique base via empty `splitName`; parse base first; split that fails archive parse inherits **base** `PackageInfo`. `setWritable(true)` on directories before walk. |
| Verified | No. |
| Needs adjustment | **Yes (ADJUST).** Inherited base signers make split signer/version checks tautological. Must verify each split’s own package, revision, signing lineage, split name, ABI, and membership. Base must be unique. Split from another package must fail. |
| Required self-test | Real config/feature split that host PM rejects still imports; unsigned / differently signed split fails; sealed-tree delete of read-only parent. Calendar splits as regression fixture. |
| Runtime regression | Split apps that previously failed import may install. Clear of sealed revisions should succeed. |
| G-ids | G03, G09 (delete-tree) |
| Verdict | ADJUST |

### 2. `app/.../VirtualPackageStateBuilder.java`

| Field | Value |
| --- | --- |
| Issue | Duplicate provider authorities dropped later provider **classes**. |
| Symptom | `getProviderInfo(ComponentName)` missed a declared provider. |
| Root cause | `appendComponents` skipped intersecting authorities. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` |
| Current modification | Always keep distinct class names. First-owner authority routing left in `VirtualPackageMetadata`. `hasProviderAuthority` is now dead. |
| Verified | No. Existing query self-test still encodes old skip behavior. |
| Needs adjustment | **Yes (ADJUST).** Remove dead helper. Align `queryContentProviders` so it does not emit two copies of the first owner. |
| Required self-test | Two provider classes, same authority: both class queries exist; authority resolves to first owner only. |
| Runtime regression | Extra PROVIDER snapshots in package state. |
| G-ids | G07, G08 |
| Verdict | ADJUST |

### 3. `app/.../MainActivity.java`

| Field | Value |
| --- | --- |
| Issue | Host UI hid exception class/message. |
| Symptom | “启动失败” without cause. |
| Root cause | `showFailure` dropped `error`. |
| Classification | `TEST_INFRASTRUCTURE` (Flash2 host logging) |
| Current modification | Log `T56-Flash2` and append `Class: message` to status. Launch gate **unchanged**. |
| Verified | Logging only. |
| Needs adjustment | Tag name is session-specific; optional. **Not** G19. |
| Required self-test | None in this file. G19 belongs in launch status contract. |
| G-ids | none (not G19) |
| Verdict | KEEP (logging). G19 still open. |

### 4. `sandbox-domain/.../BinaryXmlManifestParser.java`

| Field | Value |
| --- | --- |
| Issue | Same-class `activity` + `activity-alias` lost child intent-filters. Theme explicit vs inherited not tracked. |
| Symptom | Alias filters vanished. |
| Root cause | Children attached to a discarded pre-merge `Component`. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` |
| Current modification | `add*` now returns stored component; filters attach to merged record. Theme explicit flag. |
| Verified | Partial: domain `SelfTest` same-name alias merge. Not re-run in this session. |
| Needs adjustment | Small: need distinct-name alias fixture and disabled-then-enabled launcher fixture. |
| Required self-test | Same-name merge (exists). Distinct alias retained. Disabled launcher before enabled launcher. |
| G-ids | G01, G02 |
| Verdict | KEEP + more tests |

### 5. `sandbox-domain/.../ManifestModel.java`

| Field | Value |
| --- | --- |
| Issue | Merge API returned void; launcher picked first launcher even if disabled; alias target unused. |
| Symptom | G01/G02 filter loss; disabled alias could become launcher. |
| Root cause | `launcherActivity()` used first `launcher()` + `className()`. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` |
| Current modification | `add*` returns stored component. `launcherActivity()`: first **enabled** launcher’s `launchTargetClass()`. Theme/target merge less strict. |
| Verified | Partial (same-name alias only). |
| Needs adjustment | **Yes (ADJUST G14).** Returning **target class** rather than the alias component name may skip alias-only theme/exported/filters. Android resolver returns the **alias** `ActivityInfo`. Must become `resolveLaunchActivity(package, user)` with enabled, alias target, MAIN/LAUNCHER, priority, class existence. |
| Required self-test | Disabled launcher first + enabled later. Distinct-name alias: resolve returns alias component, launch target is target class. |
| G-ids | G01, G02, G14 |
| Verdict | ADJUST (G14) |

### 6. `sandbox-domain/.../SelfTest.java`

| Field | Value |
| --- | --- |
| Issue | No fixture for same-name alias filter merge. |
| Classification | `TEST_INFRASTRUCTURE` |
| Current modification | Adds alias `.MainActivity` + action `com.example.ALIAS`; asserts size 1 and merged action. |
| Verified | Assert exists; not run this session. |
| Needs adjustment | Insufficient for G14 / G03 / G07. |
| G-ids | G01, G02 |
| Verdict | KEEP + expand |

### 7. `sandbox-framework/.../FrameworkHooks.java`

| Field | Value |
| --- | --- |
| Issue | NFC install needs host `Context` for API36 `NfcAdapter` bootstrap. |
| Classification | `GENERAL_SYSTEM_VIRTUALIZATION` |
| Current modification | `NfcServiceHook.install(hostServiceContext, identity)`. |
| Verified | No. |
| Needs adjustment | No, if NFC hook keeps two-arg install. |
| G-ids | G13 |
| Verdict | KEEP |

### 8. `sandbox-framework/.../MediaCommunicationInvocationInterceptor.java`

| Field | Value |
| --- | --- |
| Issue | API36 `getParameters` / `getCacheParameters` fell through to unsupported. |
| Symptom | AudioManager init crash during startup. |
| Root cause | Read-query not classified. |
| Classification | `GENERAL_SYSTEM_VIRTUALIZATION` + `ANDROID_COMPAT` |
| Current modification | Those names return empty guest string. Mutations still fail-closed. |
| Verified | No. |
| Needs adjustment | Add self-test. Substring match is coarse but generic. |
| Required self-test | `getParameters`/`getCacheParameters` → `""`; `setParameters` still denied; HOST pass-through unchanged. |
| G-ids | G10 |
| Verdict | KEEP + tests |

### 9. `sandbox-framework/.../PolicyServicesInvocationInterceptor.java`

| Field | Value |
| --- | --- |
| Issue | ViewRoot API36 a11y traffic treated as mutation/deny. |
| Symptom | First render: `VIRTUAL_ACCESSIBILITY_EVENT_DENIED` / `MUTATION_DENIED:setAccessibilityWindowAttributes`. |
| Classification | `GENERAL_SYSTEM_VIRTUALIZATION` + `ANDROID_COMPAT` |
| Current modification | Blanket success for `sendAccessibilityEvent`, `interrupt`, `setAccessibilityWindowAttributes`. `allowEventDispatch` unused. |
| Verified | No. |
| Needs adjustment | **Yes (ADJUST).** Must become method-level: `APP_LOCAL_EVENT`, `APP_LOCAL_WINDOW_METADATA`, `HOST_ACCESSIBILITY_STATE_READ`, `HOST_ACCESSIBILITY_STATE_MUTATION`, `CROSS_APP_ACCESSIBILITY_DATA`, `SECURE_SETTING_MUTATION`. `interrupt` is not ViewRoot metadata. Cross-app and host mutation stay denied. |
| Required self-test | Local event success without host forward; window attributes success; other `set*` denied; secure settings denied. |
| G-ids | G11, G12 |
| Verdict | ADJUST |

### 10. `sandbox-framework/.../ReflectiveServiceHook.java`

| Field | Value |
| --- | --- |
| Issue | API36 module AIDL hides `Stub.asInterface`. |
| Symptom | NFC / Stub-based SM bind `NoSuchMethodException`. |
| Classification | `ANDROID_COMPAT` + `GENERAL_SYSTEM_VIRTUALIZATION` |
| Current modification | `resolveBinderInterface`: hidden `asInterface`, else `Stub$Proxy(IBinder)`. New `serviceManagerBindingFromStaticField`. |
| Verified | No. Hidden-API exemption helper is a no-op marker. |
| Needs adjustment | **Yes.** Static-field path is not idempotent (double-wrap on re-install). Need conversion self-tests. |
| Required self-test | Interface conversion success; descriptor correct; policy still runs; host identity not leaked; denied path still denied; no package/device special case. |
| G-ids | G13 |
| Verdict | ADJUST |

### 11. `sandbox-framework/.../SystemServiceInvocationHandler.java`

| Field | Value |
| --- | --- |
| Issue | `getHistoricalProcessExitReasons` is DUMP-protected host history. |
| Classification | `GENERAL_SYSTEM_VIRTUALIZATION` |
| Current modification | If `serviceName == "activityManager"` return `Collections.emptyList()`. |
| Verified | No. |
| Needs adjustment | **Yes (ADJUST).** Production AM name is `activity-manager`. This branch is **dead**. Live G17 is `ActivityTaskFrameworkInterceptor`. Raw `emptyList()` may be wrong if AIDL returns `ParceledListSlice`. |
| Required self-test | Live interceptor path; List vs `ParceledListSlice`; no host DUMP. |
| G-ids | G17 (dead copy) |
| Verdict | ADJUST or delete dead branch |

### 12. `sandbox-framework/.../VirtualPackageMetadata.java`

| Field | Value |
| --- | --- |
| Issue | Second provider with reused authority dropped from `byClass`. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` |
| Current modification | First-writer-wins on authority map. All distinct class names kept. Removed Quark-specific comment. |
| Verified | **No — existing `VirtualPackageQuerySelfTest` still requires `components().size() == 2` and will fail.** |
| Needs adjustment | **Yes.** Update test to keep both classes + first-owner authority. Fix query collapse. |
| Required self-test | Dual index: ComponentName → all ProviderInfo; authority → effective owner. user0/user1 isolation. Session rebuild isolation. |
| G-ids | G07, G08 |
| Verdict | ADJUST |

### 13. `sandbox-framework/.../PackageManagerInvocationHandler.java`

| Field | Value |
| --- | --- |
| Issue | Optional probes of host stub and Play Services threw `HOST_PACKAGE_HIDDEN` IAE instead of NameNotFound/`null`. |
| Symptom | Guests catching `NameNotFoundException` for GMS crashed. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` with an **`APP_OR_SDK_SPECIFIC` GMS name list** |
| Current modification | Host-stub `getServiceInfo` → null. Hard-coded `com.google.android.gms` / `gsf` / `gsf.login` → NameNotFound-shaped null/-1/false. Other hidden/non-guest identity queries still IAE. |
| Verified | No. |
| Needs adjustment | **Yes (ADJUST — section 8/9).** Not a typed visibility policy. Hard-coded GMS names are a package special case (allowed only in Compatibility Manager / tests). Must distinguish `GUEST_OWNED`, `SYSTEM_PROJECTED`, `SYSTEM_DEPENDENCY_PROJECTED`, `HOST_USER_APP_HIDDEN`, `EXPLICITLY_DENIED`. This device **has** GMS: do not synthesize a fake package, and do not dump Host GMS private data. Decide projection vs NOT_INSTALLED from Android semantics + isolation contract, not from Instagram. |
| Required self-test | Per-class matrix for `getPackageInfo`, `getApplicationInfo`, resolve/query, component lookup, UID/package identity, metadata scope. Hidden ordinary Host user apps stay hidden. |
| G-ids | G15 |
| Verdict | ADJUST |

### 14. `sandbox-framework/.../NfcServiceHook.java`

| Field | Value |
| --- | --- |
| Issue | API36 NFC Stub hidden. |
| Classification | `ANDROID_COMPAT` + `GENERAL_SYSTEM_VIRTUALIZATION` |
| Current modification | `install(Context, identity)`. If host NFC exists: `getDefaultAdapter` then wrap `NfcAdapter.sService`. Fallback: descriptor-validated SM proxy. Absent: synthetic. **Not** Guest → Host adapter passthrough. |
| Verified | No for present-NFC path. This Xiaomi reports NFC service absent (synthetic only). **`NfcServiceHookSelfTest` still calls one-arg `install` — compile break.** |
| Needs adjustment | **Yes.** Update self-test. Prove interceptor/policy still execute. Idempotent static-field wrap. |
| G-ids | G13 |
| Verdict | ADJUST |

### 15. `sandbox-runtime/.../GuestActivityController.java`

| Field | Value |
| --- | --- |
| Issue | Guest Activities used `newInstance()`, skipping `AppComponentFactory`. |
| Classification | `GENERAL_RUNTIME_DEFECT` |
| Current modification | `GuestComponentFactory.instantiateActivity(...)`. |
| Verified | Field: factory runs; Activity still fails G20. |
| Needs adjustment | Factory not cached; Service/Provider still bypass. |
| Required self-test | Factory vs fallback Activity instantiate. |
| G-ids | G18 |
| Verdict | KEEP + complete G18 |

### 16. `sandbox-runtime/.../ActivityTaskFrameworkInterceptor.java`

| Field | Value |
| --- | --- |
| Issue | `getHistoricalProcessExitReasons` forwarded to Host; API36 DUMP then ClassCast. |
| Classification | `GENERAL_SYSTEM_VIRTUALIZATION` + `ANDROID_COMPAT` |
| Current modification | Return `AndroidTaskInfoProjector.emptySlice(method)`. |
| Verified | Existing ATM self-test does not call this method. |
| Needs adjustment | Add self-test. Prefer Guest-owned virtual history when it exists. Empty list only when none. Must not catch-and-swallow `SecurityException`. |
| G-ids | G17 |
| Verdict | KEEP + tests |

### 17. `sandbox-runtime/.../AndroidTaskInfoProjector.java`

| Field | Value |
| --- | --- |
| Issue | No empty-list projector for exit-reason returns. |
| Classification | `GENERAL_SYSTEM_VIRTUALIZATION` |
| Current modification | `emptySlice` → `wrap(empty list, returnType)` (List or `ParceledListSlice`). |
| Verified | No dedicated test. |
| Needs adjustment | Add List vs PLS self-test. |
| G-ids | G17 |
| Verdict | KEEP + tests |

### 18. `sandbox-runtime/.../GuestApplicationInfoFactory.java`

| Field | Value |
| --- | --- |
| Issue | Guest `ApplicationInfo` omitted `metaData` and `appComponentFactory`. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` |
| Current modification | Copy metadata Bundle and factory name. |
| Verified | No. |
| Needs adjustment | Empty metadata stays null (Android sometimes uses empty Bundle under `GET_META_DATA`). |
| G-ids | G05, G18 |
| Verdict | KEEP + tests |

### 19. `sandbox-runtime/.../GuestComponentRuntime.java`

| Field | Value |
| --- | --- |
| Issue | Duplicate provider authority threw `PROVIDER_AUTHORITY_COLLISION`. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` |
| Current modification | Reuse first record: `PROVIDER_ALREADY_READY` / `PROVIDER_AUTHORITY_ALIASED`. Still default-constructs Provider. |
| Verified | No. |
| Needs adjustment | **Yes.** Dual index is not finished. Second class is not instantiated (authority owner only). G18 Provider factory missing. |
| Required self-test | Two classes, same authority: class query both exist; acquire stable; user0/user1 isolated; session rebuild isolated. |
| G-ids | G07, G08 |
| Verdict | ADJUST |

### 20. `sandbox-runtime/.../GuestContext.java`

| Field | Value |
| --- | --- |
| Issue | Context `ApplicationInfo` had no metadata/factory. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` |
| Current modification | Constructors take metadata + factory; storage context copies both. |
| Verified | Existing storage tests; none for metadata/factory. |
| Needs adjustment | No (plumbing). |
| G-ids | G05, G18 |
| Verdict | KEEP + tests |

### 21. `sandbox-runtime/.../GuestManifestMetadata.java`

| Field | Value |
| --- | --- |
| Issue | Only provider `<meta-data>`; values coerced to scalars; complex resources threw on API36. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` |
| Current modification | Parse application-depth metadata. Typed put. Complex `NotFoundException` → keep resource id. **Debug log of any GMS/vending key.** |
| Verified | No. |
| Needs adjustment | **Yes.** Drop GMS/vending special log. Cover activity/service/receiver metadata if Sandbox exposes those Info objects. Interceptor path still `read(assets)` without `Resources`. |
| Required self-test | primitive/string/bool/int/resource/`@0x…`/`@ref/0x…`; complex kept as resid, never forged scalar. |
| G-ids | G05, G06 |
| Verdict | ADJUST |

### 22. `sandbox-runtime/.../GuestProcessIdentityBridge.java`

| Field | Value |
| --- | --- |
| Issue | Need to know whether `ActivityThread.getProcessName()` changed after overlay. |
| Classification | `GENERAL_IDENTITY` (logging only) |
| Current modification | Log `frameworkProcess=`. **No identity semantics change.** Linux name stays Host `:guestN`. |
| Verified | Log-only. |
| Needs adjustment | Not a fix. G20 candidate: first bindApplication deviation. |
| G-ids | G20 diagnosis |
| Verdict | KEEP as diagnosis only |

### 23. `sandbox-runtime/.../GuestResourceLoader.java`

| Field | Value |
| --- | --- |
| Issue | Merged `AssetManager` on API36 resolves `AndroidManifest.xml` to last (config-split) path. |
| Symptom | Empty application metadata with Instagram `split_config.xxxhdpi`. |
| Classification | `GENERAL_PACKAGE_VIRTUALIZATION` + `ANDROID_COMPAT` |
| Current modification | Base-only AssetManager for manifest parse; merged assets for resources. Full metadata dump to logcat. |
| Verified | No. |
| Needs adjustment | **Yes.** Drop production metadata dump. Keep base-manifest / merged-resources split. |
| Required self-test | Base+split fixture: package identity from base; resources from merged table. |
| G-ids | G04, G05 |
| Verdict | ADJUST |

### 24. `sandbox-runtime/.../GuestRuntimeEnvironment.java`

| Field | Value |
| --- | --- |
| Issue | Application constructed with `newInstance()`; no factory/metadata into `GuestContext`. |
| Classification | `GENERAL_RUNTIME_DEFECT` + **`APP_OR_SDK_SPECIFIC` probe** |
| Current modification | Archive `appComponentFactory` via Host `getPackageArchiveInfo`; factory-instantiate Application; after `onCreate`, load Instagram ReDex types `X.0IA` / `X.1ro`. `GUEST_PREPARED` still returned regardless of Activity create/resume. |
| Verified | Field: factory name and Application instantiate work. Launch is not PASS. |
| Needs adjustment | **Yes (REMOVE probe).** `X.0IA`/`X.1ro` must leave Core. Factory should come from base Manifest, not fail-open Host archive parse. G19 still open. |
| Required self-test | Fixture factory for Application; missing factory fallback; no Instagram class names. |
| G-ids | G18, G05, G19, G20 |
| Verdict | ADJUST + REMOVE Instagram probe |

### 25. `sandbox-runtime/.../GuestStorageNameCodec.java`

| Field | Value |
| --- | --- |
| Issue | Concurrent first-touch created empty legacy + v2 → `LEGACY_AND_V2_BOTH_EXIST:app_minidumps`. |
| Classification | `GENERAL_RUNTIME_DEFECT` |
| Current modification | If both exist and **source is empty**, delete source and keep v2. Both populated still fail-closed. |
| Verified | Existing tests cover file collisions, not this empty-dir race. |
| Needs adjustment | **Yes.** Empty target + populated source still fail-closed (remaining race). Need concurrent mkdir, empty legacy, populated legacy, populated v2, both populated, crash/retry. |
| G-ids | G16 |
| Verdict | ADJUST |

### 26. `sandbox-runtime/.../GuestComponentFactory.java` (untracked new)

| Field | Value |
| --- | --- |
| Issue | No shared helper for `android:appComponentFactory`. |
| Classification | `GENERAL_RUNTIME_DEFECT` |
| Current modification | `instantiateApplication` + `instantiateActivity`. New factory instance per call. No Service/Receiver/Provider/classloader. |
| Verified | Field Application path works for Instagram factory name. |
| Needs adjustment | **Yes.** Cache one factory per process (LoadedApk singleton). Instantiate every component type the Sandbox actually constructs. |
| Required self-test | custom factory → Application, Activity, and every type Sandbox instantiates. |
| G-ids | G18 |
| Verdict | ADJUST |

### Untracked non-source

| Path | Verdict |
| --- | --- |
| `docs/product/T56_XIAOMI_DEVICE_RESOLUTION.md` | Keep as R01 device lock note. Not a Core behavior branch. |
| `artifacts/m5-device-lab-rd-t56/` | Evidence. Do not commit unless project rules require. |

---

## G01–G20 register

| Id | Topic | Classification | In tree? | Verified | Verdict |
| --- | --- | --- | --- | --- | --- |
| G01 | Activity / activity-alias normalization | GENERAL_PACKAGE_VIRTUALIZATION | Yes (parser + model) | Partial SelfTest | KEEP + expand tests |
| G02 | Alias intent-filter merge timing | GENERAL_PACKAGE_VIRTUALIZATION | Yes | Partial SelfTest | KEEP |
| G03 | Split APK package revision parsing | GENERAL_PACKAGE_VIRTUALIZATION | Partial (inherit base PackageInfo) | No | ADJUST — do not trust split via base stamp |
| G04 | Base Manifest vs split resources | GENERAL_PACKAGE_VIRTUALIZATION + ANDROID_COMPAT | Yes (base-only AssetManager) | No | ADJUST — drop dump; add fixture |
| G05 | ApplicationInfo.metaData projection | GENERAL_PACKAGE_VIRTUALIZATION | Yes | No | KEEP + tests; drop verbose dump |
| G06 | Typed / complex metadata | GENERAL_PACKAGE_VIRTUALIZATION | Yes (complex → resid) | No | KEEP + tests |
| G07 | Provider component index | GENERAL_PACKAGE_VIRTUALIZATION | Partial | Existing test will fail | ADJUST dual index |
| G08 | Duplicate Provider authority | GENERAL_PACKAGE_VIRTUALIZATION | Partial first-owner | No | ADJUST |
| G09 | Guest Clear Data parent / process cleanup | GENERAL_RUNTIME_DEFECT | Partial (`setWritable` + do not delete parent) | No | ADJUST — security suite required |
| G10 | Audio read-query classification | GENERAL_SYSTEM_VIRTUALIZATION | Yes | No | KEEP + tests |
| G11 | Accessibility window metadata | GENERAL_SYSTEM_VIRTUALIZATION | Partial (blanket success) | No | ADJUST method-level |
| G12 | Accessibility app-local View events | GENERAL_SYSTEM_VIRTUALIZATION | Partial (blanket success) | No | ADJUST method-level |
| G13 | API36 NFC Binder conversion | ANDROID_COMPAT + GENERAL_SYSTEM_VIRTUALIZATION | Yes; not Host passthrough | Self-test compile-broken; device NFC absent | ADJUST tests + idempotency |
| G14 | Launcher resolution | GENERAL_PACKAGE_VIRTUALIZATION | Partial (`launchTargetClass`) | No | ADJUST generic resolver |
| G15 | External package visibility | GENERAL_PACKAGE_VIRTUALIZATION | Partial + GMS name list | No | ADJUST typed policy; remove GMS special case from Core |
| G16 | Legacy/v2 Guest storage race | GENERAL_RUNTIME_DEFECT | Partial (empty source only) | No | ADJUST remaining races + tests |
| G17 | getHistoricalProcessExitReasons | GENERAL_SYSTEM_VIRTUALIZATION | Live interceptor + dead AM branch | No | KEEP interceptor; fix/delete dead branch |
| G18 | AppComponentFactory | GENERAL_RUNTIME_DEFECT | Application + Activity only | Field Application only | ADJUST complete lifecycle |
| G19 | Guest launch PASS gate | GENERAL_RUNTIME_DEFECT | **Not implemented.** `GUEST_PREPARED` still success | No | ADJUST rewrite gate |
| G20 | IgSessionManager initialization | Unknown until bindApplication compared | Diagnosis logs only; Instagram `X.0IA` probe | Still FAIL on device | Diagnose vs Android 16; remove Instagram probe |

### Extra findings (not in original G01–G20)

| Extra | Topic | Classification | Verdict |
| --- | --- | --- | --- |
| X01 | `X.0IA` / `X.1ro` Instagram ReDex probe in `GuestRuntimeEnvironment` | APP_OR_SDK_SPECIFIC | **REMOVE from Core** |
| X02 | Full application metadata dump (`CS_GUEST_METADATA`) | TEST_INFRASTRUCTURE leaked to production | Remove or gate |
| X03 | GMS/vending key special log in `GuestManifestMetadata` | APP_OR_SDK_SPECIFIC logging | Remove |
| X04 | `NfcServiceHookSelfTest` one-arg `install` | TEST_INFRASTRUCTURE | Fix before any NFC commit |
| X05 | `VirtualPackageQuerySelfTest` still expects dropped duplicate provider | TEST_INFRASTRUCTURE | Update with dual-index contract |
| X06 | Linux/ActivityThread process name remains Host `:guestN` | GENERAL_IDENTITY | G20 first-deviation candidate |

---

## Package / device special-case search

Forbidden as Core behavior conditions: `com.instagram.android`, `com.google.android.calendar`, `com.quark.browser`, `com.alibaba.android.rimet`, `Xiaomi`, `HyperOS`, `25019PNF3C`.

| Location | Finding |
| --- | --- |
| `GuestRuntimeEnvironment.logGuestApplicationState` | **Present:** Instagram ReDex `X.0IA` / `X.1ro`. Remove. |
| `PackageManagerInvocationHandler.isUnavailablePlayServicesDependency` | **Present:** `com.google.android.gms` / `gsf` / `gsf.login`. Must become generic dependency policy. |
| `GuestManifestMetadata` | Logs keys containing `google.android.gms` / `com.android.vending`. Remove. |
| Remaining modified Java | No Instagram / Calendar / Quark / DingTalk / Xiaomi / HyperOS / `25019PNF3C` behavior branches. |
| `ref/upstream/NewBlackbox` | Historical Instagram crash lists. Not in this dirty tree. Do not copy. |

---

## Current Instagram blocker (G20)

Latest probe `build/t56-r01-xiaomi/flash2/instagram-state-probe-logcat.txt` (2026-08-14 13:13):

1. `ActivityThread` processName = `com.warden.controlledsandbox.debug:guest2`.
2. Factory `Ig4aAppComponentFactory` instantiates `InstagramAppShell`.
3. Virtual process overlay reports `package=com.instagram.android`.
4. Some providers `PROVIDER_READY` (`SecureFileProvider`, `FileProvider`, `AndroidXAppInitializer`).
5. `GUEST_PREPARED` for `InstagramMainActivity`.
6. Host `StubActivity2` binds; guest Activity created later via `postGuestCreationIfResumed`.
7. Activity constructor: `QPLProvider` not installed.
8. `InstagramMainActivity.onCreate` → `IgSessionManager not initialized`.

This is **not** proven Instagram-private yet. First suspected generic deviations vs API36 `ActivityThread.handleBindApplication`:

- Linux / `ActivityThread` process name is Host guest slot, not guest package.
- Application / provider / `onCreate` vs Activity instantiate order (Activity created after stub resume, not in the same bindApplication pipeline).
- AppComponentFactory is a new instance per call, not a LoadedApk singleton.
- Providers instantiated without factory.
- `GUEST_PREPARED` is recorded before a real Activity create/resume.

No sleep, retry, or `IgSessionManager` field write will be added.

---

## Commit split (after ADJUST + self-tests, not now)

Proposed independent commits once each cluster compiles and its tests pass:

1. `fix: correct manifest component and split package parsing` — G01 G02 G03 G04
2. `fix: preserve typed guest application metadata` — G05 G06
3. `fix: separate provider component and authority ownership` — G07 G08
4. `fix: harden guest instance data cleanup` — G09 G16
5. `fix: align peripheral framework query semantics` — G10 G17
6. `fix: align API36 accessibility guest-local events` — G11 G12
7. `fix: harden Android16 NFC interface projection` — G13
8. `fix: implement generic package visibility policy` — G15
9. `fix: align guest application component factory lifecycle` — G18
10. `test: harden real guest activity launch acceptance` — G14 G19

Do not commit “fix Instagram”. Do not amend / rebase / squash / force-push.

---

## Immediate work order (R02)

1. Remove X01 Instagram ReDex probe and GMS/vending special logs from Core.
2. Replace G15 GMS name list with typed visibility policy.
3. Finish G07/G08 dual index + fix contradictory self-test.
4. Method-level G11/G12.
5. G13 self-test + idempotency (no Host passthrough).
6. G03 split revision/signing (stop inheriting base signers).
7. G14 `resolveLaunchActivity`.
8. G09/G16 security + race tests.
9. G18 factory singleton + all Sandbox-created component types.
10. G19 launch PASS gate.
11. Then collect real bindApplication order for G20.
