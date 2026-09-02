# T57-R03-P2 — Package / PMS / Split / Loader / ABI Convergence

Task: `T57-R03-P2-PACKAGE-LOADER-ABI-CONVERGENCE`  
Branch: `feature/t57-r03-va-pro-capability-campaign`  
Scope: controlled Android sandbox substrate only. No XH-specific package logic, OEM adapter,
P3 Native/Seccomp work, or P4 GMS/SystemService work was added.

## 1. Baseline

| Field | Value |
|---|---|
| START_HEAD | `a3714a8060c5dfe243e0468c29bbe819d2fae5e3` |
| START_TREE | `a8cfba2df0fb5e5c6380e46307007e6a85a839b3` |
| Branch | `feature/t57-r03-va-pro-capability-campaign` |
| FINAL_HEAD (production commit) | `77b38eea4670a64efd969154334f4f14374be873` |
| FINAL_TREE (production commit) | `bb2087ead99176d9160bdda92642918d10a85979` |
| Report path | `reports/t57-r03/p2-package-loader-abi/T57_R03_P2_PACKAGE_LOADER_ABI_CONVERGENCE_REPORT.md` |

The final documentation commit is intentionally listed separately in the terminal receipt: a Git
commit cannot contain its own resulting hash. The production commit above is the final source
tree covered by this report; the report commit does not alter production behavior.

## 2. Root Causes Found and Closed

1. The debug/runtime import path resolved only `ApplicationInfo.sourceDir`. A package installed
   with feature, density, language, or ABI splits was therefore published as a base-only CAS
   revision. The path now resolves the complete installed artifact set and enters the same
   multi-artifact transaction as an explicit install.
2. Modern split APKs can be verified by platform PMS with v2/v3 signing while `JarFile` exposes
   no legacy certificate entry. The importer rejected an otherwise platform-verified split. It
   now inherits the base signer only for an exact, currently installed source path; arbitrary
   external unsigned/uninspectable splits remain fail-closed.
3. The default framework `android.app.Application` was sent through the Guest-defining loader.
   That made a valid APK fail because the framework class is not guest-defined. Framework-owned
   default Application is now instantiated by the framework path; declared Guest
   AppComponentFactory still owns Guest component creation.
4. Java-only packages entered the native policy with an empty ABI. Native bootstrap now separates
   `containsNativeCode` from process policy and uses the host ABI for the process boundary when no
   APK `.so` exists.
5. Package projections could retain optional parsed/host fields, and peer package context cache
   keys did not include package revision. Projections now start from virtual state and code/resource
   context keys include package revision plus include-code mode.
6. Destructive debug lifecycle commands re-imported the physical APK before clear/delete. They now
   operate on the authoritative virtual record, so clear/delete remain meaningful after a physical
   replacement or uninstall.

## 3. Architecture

```text
APK / Split APK
    -> ApkImportManager: parse, signature, ABI/ELF and dependency validation
    -> PackageArtifactRecord[]: base + ordered split set and per-artifact digest
    -> SandboxPackageLifecycle / PackageLifecycleTransaction:
       atomic install, replacement, clear-instance, delete-instance, retention cleanup
    -> VirtualPackageUniverse / VirtualPackageMetadata:
       virtual PMS identity, visibility, components, signatures, installer and split projection
    -> immutable package revision + session/generation fence
    -> RuntimeGuestLifecycleCoordinator / GuestRuntimeEnvironment:
       bindApplication-style process bootstrap and revision verification before native setup
    -> Guest ApplicationInfo / LoadedApk-equivalent projection
    -> GuestComponentFactory / AppComponentFactory
    -> GuestDexBufferLoader / GuestClassLoader:
       base + split DEX/resource paths, declared-class boundary and revision-scoped context cache
    -> NativePolicy / NativeLoader boundary:
       selected ABI, virtual native library path, JNI load and process ABI contract
    -> Guest component runtime
```

Android ART/OAT remains the platform owner. CAS supplies the validated revision, FD-backed DEX
and resource inputs, virtual `ApplicationInfo`, native path and process identity; it does not
reimplement ART, OAT compilation, or linker internals.

## 4. Source-Level Gap Audit

The audit covered the requested symbols and paths: `PackageManager`, `PackageParser`,
`PackageInfo`, `ApplicationInfo`, `ActivityInfo`, `ServiceInfo`, `ProviderInfo`, `ResolveInfo`,
`SigningInfo`, `Signature`, `InstallSourceInfo`, `PackageInstaller`,
`PackageInstallerSession`, `splitNames`, `splitSourceDirs`, `sourceDir`, `publicSourceDir`,
`nativeLibraryDir`, `primaryCpuAbi`, `secondaryCpuAbi`, `sharedLibraryFiles`, `queries`, and
visibility/revision terms.

| Area | Evidence in CAS | P2 decision/result |
|---|---|---|
| Package authority | `VirtualPackageUniverse`, `VirtualPackageMetadata`, `RuntimePackageAuthority`, `SandboxCatalogState` | Keep one virtual package universe as authority; host PMS is only an input to explicit import, never a Guest fallback. |
| Parsing/install | `ApkImportManager`, `SandboxPackageLifecycle`, `PackageInstallSessionStore`, `PackageLifecycleTransaction` | Validate before publication; publish only an executable immutable revision. Full artifact sessions are supported; `MODE_INHERIT_EXISTING` remains explicit fail-closed. |
| PMS projection | `PackageManagerInvocationHandler`, `HiddenPackageResultMapper`, `GuestPackageMetadataMapper` | Package, component, permission, installer, path and UID results are projected from virtual state. Host `ApplicationInfo` is not authoritative. |
| Visibility | `VirtualPackageUniverse`, `VirtualPackageVisibilityPolicy`, guest intent/provider resolver | Package queries, intent resolution and provider authority resolution use the same virtual visibility decision. Self and explicit package visibility remain available; undeclared implicit results are filtered. |
| Signing | `VirtualPackageStateSnapshot`, `VirtualPackageMetadata` | Current signer bytes/digest are immutable and projected to `GET_SIGNATURES` and `GET_SIGNING_CERTIFICATES`. `SigningInfo` has no invented history; a real signer-history field is deferred. |
| Loader | `GuestRuntimeEnvironment`, `GuestApplicationInfoFactory`, `GuestComponentFactory`, `GuestContext`, `GuestPackageContext` | LoadedApk-equivalent state is bound to package revision, split paths, virtual identity and a distinct class/resource mode. |
| Native/ABI | `ApkImportManager`, `GuestRuntimeEnvironment`, `NativePolicy`, existing companion routing | ELF class/machine and selected ABI are validated; unsupported native ABI fails closed. Deep linker/native interception remains P3. |

### PMS query semantics

The following calls were audited against the virtual universe and retained in the deterministic
query tests: `getPackageInfo`, `getApplicationInfo`, `getActivityInfo`, `getServiceInfo`,
`getProviderInfo`, `getReceiverInfo`, `resolveActivity`, `resolveService`,
`resolveContentProvider`, `queryIntentActivities`, `queryIntentServices`,
`queryBroadcastReceivers`, `queryContentProviders`, installed package/application lists,
package UID, permission and signature queries. `InstallSourceInfo` now targets the requested
virtual package rather than the caller's metadata or a host installer.

Guest-visible `sourceDir`, `splitSourceDirs`, `nativeLibraryDir`, UID, installer and component
metadata come from the CAS projection. Host package paths and Host UID are not used as a fallback.
The synthetic peer-context paths are virtual Guest paths, not physical Host paths.

### Package visibility and `<queries>`

Manifest package queries, package-name queries, intent queries, provider-authority queries,
explicit package intents, self visibility and system-policy visibility are resolved through the
same `VirtualPackageUniverse`/projection path. There is no separate Host resolver branch for a
Guest result. Deterministic package visibility and Intent resolver tests pass.

### Identity, installer and revision

`firstInstallTime`, `lastUpdateTime`, version name/code, installer package, current signer and
the aggregate package revision are carried together in the immutable package record. A package
replacement switches the catalog only after the new base/split set and native extraction have
validated. The old record is retained only where lifecycle transaction/rollback retention needs
it, and the runtime generation fence rejects stale sessions.

Signer history is not fabricated: the current CAS record does not yet carry a verified rotation
lineage, so the `SigningInfo` projection uses the current signer set with empty history. This is
an explicit compatibility boundary, not a Host signature leak.

## 5. Capability Results

| Capability | Result | Evidence/contract |
|---|---|---|
| PACKAGE_MANAGER | PASS | Virtual PackageInfo/ApplicationInfo/component/query projection; new split/signature self-test. |
| PACKAGE_VISIBILITY | PASS | Virtual package, intent and provider resolution share the virtual universe. |
| SIGNATURE | PASS_CURRENT / DEFERRED_SIGNER_HISTORY | Current signer bytes/digest projected to legacy and modern fields; no invented history. |
| INSTALLER | PASS | Installer and InstallSourceInfo are sourced from target virtual metadata. |
| INSTALL | PASS | Parse -> validate -> stage -> native extract -> atomic catalog publication; invalid native ABI and invalid artifact sets fail closed. |
| UPGRADE | PASS | Base/split replacement is a new immutable revision; bootstrap verifies all artifact bytes before native state; session/generation fencing rejects stale runtime. |
| CLEAR | PASS | Clear removes the selected instance data root while retaining package/install metadata, policy and revision. Process/component cleanup is performed by the existing stop/generation path. |
| DELETE | PASS | Delete removes instance/package authority, component/runtime state, pending cleanup references, split/native revision files and unreferenced data through lifecycle cleanup. |
| REINSTALL | PASS | Manual API32 sequence verified install -> run -> uninstall -> physical reinstall -> run with a new process/session/revision. |
| SPLIT_APK | PASS_FULL_SET | Base + feature split parser, source/public dirs, component resolver, DEX/resource path and class loading verified. Full replacement supports split changed, added and removed sets. |
| DYNAMIC_FEATURE | PASS_BASELINE / DEFERRED_DYNAMIC_FEATURE_RUNTIME | Installed `onDemand=false` dynamic-feature fixture works as an installed split. Runtime add/remove after process start is not claimed safe and is deferred as `DEFERRED_DYNAMIC_FEATURE_RUNTIME`; restart/reload is the contract. |
| LOADED_APK | PASS | Guest process receives one explicit package revision, virtual ApplicationInfo and split-aware resource/code inputs. |
| APP_COMPONENT_FACTORY | PASS | Declared Guest factory receives the Guest ClassLoader for Application/Activity/Service/Receiver/Provider; framework default Application is handled by framework ownership. |
| CLASSLOADER | PASS | Base + split class path is FD-backed and revision-bound; no package-name-only peer cache remains. |
| CLASSLOADER_REVISION_FENCING | PASS | `GuestContextCacheKeySelfTest` proves revision and code/resource mode separation; process death reconstructs from current authority. |
| PROCESS_RELOAD | PASS | API32 process death/relaunch and API35/API36 targeted relaunch use current package revision. |
| NATIVE_LIBRARY | PASS_TARGETED | `System.loadLibrary` fixture loads on API35/API36 x86_64; missing/unsupported ABI and malformed ELF fail closed. |
| ABI_32_64 | PASS_CONTRACT | `arm`, `arm64`, `x86`, `x86_64` ELF selection is explicit; existing 64 ordinary/16 isolated slots and companion architecture are unchanged. Foreign ABI on unsupported x86_64 fixture fails closed. |
| LINKER_NAMESPACE | PASS_BOUNDARY | Legal Guest library search is scoped to the selected virtual native directory and platform loader; raw linker manipulation/anti-detection is deferred to P3. |
| PACKAGE_REVISION_FENCING | PASS | Revision verification moved before NativePolicy/IO/crash setup and covers base/split bytes and native state. |
| ACTIVITY_FIX03_REGRESSION | PASS | No ActivityTaskLedger/FIX03 production changes; static tests and API32 lifecycle smoke remain green. |
| P1_BINDER_COMPONENT_REGRESSION | PASS | No Binder substrate, returned Binder, Service/Job/Broadcast/Provider/PendingIntent, slot or virtual/physical identity contract changes. |

### Install lifecycle smoke sequence

On API32 `RD测试` (serial resolved dynamically by `scripts/mumu_instance.py`), the fixture
sequence passed with controlled spacing between commands:

```text
fresh v1 install -> launch -> v2 install/upgrade -> v2 launch/relaunch
-> clear data -> relaunch -> delete/uninstall -> physical reinstall v1 -> relaunch
```

The runner can deliver a subsequent `onNewIntent` while an earlier launch command is still
writing its result file; the underlying lifecycle log showed the correct delete/clear result. This
is recorded as integration verification debt, not a production package failure.

### Split fixture smoke

The generic fixture is `com.warden.controlledsandbox.fixture.split` with base
`fixture-split-base` and dynamic feature `fixtureSplitFeature`. On API32, API35 and API36:

* `pm path` exposed base plus the feature split;
* import/prepare succeeded through the multi-artifact pipeline;
* base Activity logged `featureClassLoaded=true`;
* feature Activity logged `FEATURE_CREATE classLoaded=true`.

The first two failures were real and were fixed: default framework Application was incorrectly
sent through Guest defining-loader, and split certificate lookup rejected a platform-verified
v2/v3 split. No fake split success or catch-and-continue path was added.

## 6. VA / NBB Reference Comparison

Local reference sources audited under `ref/upstream/VirtualApp` and
`ref/upstream/NewBlackbox`; the implementations below are design references, not mechanical
copies.

| Capability | VA | NBB | Current CAS | This-round decision |
|---|---|---|---|---|
| Virtual package authority | `VPackageManagerService`, `VAppManagerService`, `PackagePersistenceLayer` | `BPackageManagerService`, `BPackage` | `VirtualPackageUniverse`, `VirtualPackageMetadata`, `SandboxCatalogRepository` | Keep CAS's immutable snapshot/transaction ownership and virtual-only projection. |
| Install/session | `VPackageInstallerService`, `VAppManagerService` | `BPackageInstallerService`, package monitor | `PackageInstallSessionStore`, `SandboxPackageLifecycle`, `ApkImportManager` | Use full artifact sets and atomic publication; keep unsupported inherit-existing explicit. |
| Parser/splits | `PackageParserEx`, split-aware package records | `ComponentResolver`, `BPackage` | `PackageArtifactRecord`, dependency-ordered split verifier and `ApplicationInfo` split projection | Model base/split as one revision; no base-only fallback. |
| Loader/LoadedApk | `VClientImpl` and LoadedApk mirrors | `BActivityThread` and LoadedApk bridge | `GuestRuntimeEnvironment`, `GuestApplicationInfoFactory`, `GuestContext`, `GuestComponentFactory` | Bind one Guest process to one revision and preserve framework factory lifecycle. |
| Native/ABI | `NativeLibraryHelperCompat`, VA's 32/64 dual-package approach | native/loader helpers in `Bcore` | `ApkImportManager` ELF validation, `NativePolicy`, existing 32/64 companion route | Validate ABI and path at install/bootstrap; defer native interception. |
| Lifecycle cleanup | package monitor and persisted package state | package monitor and installer state | catalog transaction, process generation fencing, data/revision sweep | Preserve existing CAS ownership; close stale revision and split state without copying VA internals. |

References: [VirtualApp](https://github.com/asLody/VirtualApp),
[VirtualApp English README](https://github.com/asLody/VirtualApp/blob/master/README_eng.md),
[NewBlackbox](https://github.com/ALEX5402/NewBlackbox).

## 7. VA PRO Gap Mapping

The VA PRO README/changelog items relevant to this campaign were grouped into four root causes:

| Root cause | Gap closed in P2 | Remaining boundary |
|---|---|---|
| Package lifecycle | Package install, replacement, clear/delete/reinstall and stable virtual installer/signature/revision projection. | Full PackageInstaller inherit-existing mode and signer rotation history are deferred. |
| Split | Google Play-style installed split discovery, base+split revision set, split source dirs, feature component resolution and class/resource loading. | Runtime dynamic-feature mutation after process start is `DEFERRED_DYNAMIC_FEATURE_RUNTIME`. |
| Loader | LoadedApk-equivalent identity, default/declared AppComponentFactory, split ClassLoader, process reload and revision cache fencing. | WebView/GMS/system-app special integration is outside P2/P4. |
| ABI/native library | Native library extraction, ELF class/machine validation, selected ABI path, JNI load and unsupported companion fail-closed behavior. | Linker namespace customization, raw linker/native interception, 16KB-specific native remediation and anti-detection belong to later work. |

The relevant commercial themes include split APK/Google Play split fixes, install/upgrade and
uninstall/reinstall fixes, class linker/DEX/OAT changes, WebView/system component loading,
32/64-bit support, linker/native library changes, and 16KB page-size updates. They were used as
gap evidence only; no OEM or P3/P4 campaign was started.

## 8. Tests and Verification

### Deterministic/source tests

`python tools/static_android_compile.py` passed, including:

* transactional package lifecycle revision self-test;
* immutable multi-APK revision-set verifier;
* dependency-ordered split artifact self-test;
* virtual package visibility/query/Intent resolver tests;
* virtual PackageInfo split/signature projection self-test;
* Guest package-context revision cache-key self-test;
* Guest AppComponentFactory lifecycle self-test;
* Guest native runtime projection and ABI routing self-tests.

### Build and audit

| Check | Result |
|---|---|
| `python tools/static_android_compile.py` | PASS |
| `python tools/android_gradle_build_gate.py verify --timeout-seconds 1200` | PASS Android Gradle/AIDL/CMake/APK build gate |
| `python tools/capability/run_local_capability_audit.py --all` | Diagnostic exit 1 because 13 pre-existing `KNOWN_ISSUE` gates; `NEW_REGRESSION=0`, total 42, PASS 29, KNOWN_ISSUE 13, FAIL 13, UNVERIFIED 0. Raw audit output is generated locally and is not retained in the source tree. |
| `git diff --check` | PASS |

The audit's known issues remain outside this P2 production campaign: architecture-size debt,
SBOM, broadcast/system-service debt, native trust-boundary debt, package boundary/isolated
process debt, existing APK revision-binding checker debt, ownership/atomic cleanup debt, and
split-install evidence debt. The package-lifecycle checker and architecture generated-build
false positives were corrected so this change set reports no new regression.

### Device matrix

| Device | Smoke result |
|---|---|
| API32 MuMu `RD测试` | PASS lifecycle sequence, split base/feature parse and class loading, process reload; foreign unsupported native ABI rejected fail-closed. Serial was resolved dynamically, not hardcoded. |
| API35 `T57_R03_API35_x86_64` | PASS targeted package parse/install, split base+feature class loading, AppComponentFactory path, x86_64 native `JNI_OnLoad`/library load. |
| API36 `T57_R03_API36_x86_64` | PASS targeted package parse/install, split base+feature class loading, AppComponentFactory path and x86_64 native prepare/JNI load. Existing Activity window-confirmation runner gate remains integration debt. |
| API37 | `DEFERRED_API37`; no available official AVD was used and it does not block P2. |

Platform contract references: [ApplicationInfo](https://developer.android.com/reference/android/content/pm/ApplicationInfo),
[PackageInfo](https://developer.android.com/reference/android/content/pm/PackageInfo),
[PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager),
and [Android ABIs](https://developer.android.com/ndk/guides/abis.html).

## 9. Production Blockers

`NONE`.

No Guest identity leak, fake install success, stale upgrade ClassLoader/native library, stale
uninstall/reinstall process, split parse/load failure, AppComponentFactory production error,
ABI mismatch acceptance, or P1 Activity/Binder regression remains in the exercised P2 path.

## 10. Deferred Integration Issues

* Debug runner `onNewIntent`/result-file concurrency can report the prior command when commands
  are sent without spacing; manual spaced commands and logcat are correct.
* API35/API36 runner/evidence acceptance gates (including API36 Activity window confirmation) need
  integration verification cleanup.
* API37 coverage is `DEFERRED_API37`.
* `MODE_INHERIT_EXISTING` incremental PackageInstaller sessions are explicit fail-closed and
  deferred; full replacement artifact sets support split add/remove/change.
* Dynamic feature runtime add/remove after process start is `DEFERRED_DYNAMIC_FEATURE_RUNTIME`.
* Existing audit/review-pack/evidence known issues remain tracked as Integration Verification Debt.
* OEM differences and 16KB page-size validation are deferred.
* Native linker namespace customization, deep Native interception, Seccomp and anti-detection are
  P3; GMS/SystemService/WebView-special battle is P4.

## 11. Internal Review

The production diff was checked for package-state ownership, immutable revision ownership, split
ownership, ClassLoader cache key completeness, process/package coupling, native path/ABI
selection, stale state, duplicate coordinators and God-class expansion. No package-name/APK-path
fixture hardcode, signature bypass, ABI disable, universal ClassLoader, catch-and-success, or
permission expansion was introduced. Existing ordinary process slots remain 64 and isolated slots
remain 16.

## 12. Commits and Next

* `77b38eea4670a64efd969154334f4f14374be873` — `feat(package): converge virtual package lifecycle and split loader`
* This fixed-path report is the terminal documentation change for the task and is recorded in the
  final receipt after commit.

`NEXT: WAIT_FOR_NEXT_TASK`

No P3, P4, Integration Verification, OEM, SX or XH work was started automatically.
