# P2-02 reference decision — Chrome split-context failure localization

**Status:** `ACCEPTED` — 2026-09-10 (Asia/Shanghai)

## First effective result

The Xiaomi Chrome revision frozen by P2-01 imported as one broker-verified
base APK plus `split_chrome`, `split_config.zh`, and `split_on_demand`.  The
current platform PackageManager shared-library projection also resolved the
system static-library catalog record `com.google.android.trichromelibrary`,
version `777818033`, with its system provider path.  Thus neither a missing
split artifact nor an omitted Trichrome provider is a supported explanation
for the first failure.

The first no-retry CAS launch reached `SplitChromeApplication`, installed the
Guest `LoadedApk` projection, and then failed in `Application.onCreate`:

```
java.lang.NullPointerException: Attempt to invoke virtual method
'java.lang.Class java.lang.Object.getClass()' on a null object reference
  at org.chromium.base.BundleUtils.e(BundleUtils:12)
  at org.chromium.base.BundleUtils.a(BundleUtils:102)
  at org.chromium.base.BundleUtils.b(BundleUtils:26)
  at ...SplitChromeApplication.onCreate(...:6)
```

The last confirmed success stage is `APPLICATION_ATTACH_BASE_CONTEXT` followed
by `PROVIDER_PREPARE`; this is a Java application-bootstrap failure, not a
native linker, RELRO, JNI, renderer, or first-frame result.

## Read references and contracts

| Source | Method / behavior read | CAS counterpart | Decision |
| --- | --- | --- | --- |
| NBB | `BActivityThread.handleBindApplication` establishes virtual `ApplicationInfo`, package context and `LoadedApk` before application creation. `PackageManagerCompat.fixJar` is only an Apache legacy-library case. | `GuestLoadedApkBridge.install` and `GuestRuntimeEnvironment` install the platform-facing `LoadedApk` projection before creating/binding the Guest application. | Keep the framework bind order; do not add a Chrome/Trichrome special directory or copy the Apache-only workaround. |
| VA OSS | `VClientImpl.bindApplication` obtains `LoadedApk.makeApplication`, installs providers, then calls application startup. `PackageParserEx.initApplicationInfoBase` obtains `sharedLibraryFiles` from an unhooked host PackageManager for system-dependent packages. | `GuestApplicationInfoFactory` projects broker-verified application paths and `GuestSharedLibraryPathResolver` reads the active platform shared-library catalog. | The existing static provider projection is present; it must not be replaced by a guessed host-path rule. |
| VA OSS | `IOUniformer.cpp` hooks historical linker entry points. | CAS native loader/binding boundary uses the current platform `PathClassLoader` and validated native artifacts. | The observed Java `BundleUtils` failure is before any evidence of `dlopen`/RELRO/JNI failure; no native hook change is justified. |
| Chromium `refs/branch-heads/7499` `BundleUtils.createIsolatedSplitContext` / `cacheAndValidateSplitClassLoader` | For an installed split, Chromium calls `applicationContext.createContextForSplit(splitName)`, then immediately evaluates `splitContext.getClassLoader().getParent()` and compares the result with application/module loaders. | `GuestContext.createContextForSplit` currently returns the same `GuestContext` for every known split. | This is a concrete contract mismatch candidate, but not yet a confirmed cause: capture the actual process/application/split loader identities and parent values first. |

The Chromium source was used only to identify the public Android contract and
the exact dereference sequence; the installed Chrome stack is obfuscated and
does not by itself prove which object was null.

## Probe result and chosen minimal correction

The no-retry probe observed three Chrome requests for split `chrome`.
`GuestContext` returned itself and its process loader was a valid
`PathClassLoader`, but its parent was the Host process loader.  That differs
from both Chromium's Chrome-module loader and its application loader, so
`BundleUtils.cacheAndValidateSplitClassLoader` entered its replacement path.
It walks `ContextWrapper.getBaseContext()` until a `ContextImpl` and invokes
`getClass()` to reflectively set `mClassLoader`.  CAS deliberately exposes a
finite Guest-only unwrap boundary whose final base is `null`; Chromium then
attempted `null.getClass()`, matching the captured NPE exactly.

The correction is therefore narrow and package-neutral: for each already
broker-verified split, `GuestContext.createContextForSplit` caches a distinct
Guest-only `Context` whose `PathClassLoader` is constructed from that verified
split path with the current Guest process loader as parent.  Its parent
identity satisfies Chromium's validation, so the split-cache path never takes
the reflective replacement path.

The device DEX disassembly then established a second, independent use of the
same Chromium helper: its service `attachBaseContext(Context)` passes the
ordinary component Context to an internal factory, which unconditionally walks
all `ContextWrapper` bases and reflects a field named `mClassLoader`.  Thus a
finite terminal that itself extends `ContextWrapper` still ends in
`null.getClass()`.  The terminal must instead be a non-`ContextWrapper`,
Guest-only `Context` with a local mutable `mClassLoader` field.  It delegates
only to `GuestContext` and exposes no Host transport, so Chromium can complete
its documented loader-cache repair without observing or mutating a Host
Context.

This adds no Chrome-specific directory, no unverified artifact, no native
hook, and no trust/permission/timeout change.  It will be compiled and first
verified against the same frozen Chrome revision before any broader test.

## Native static-provider failure and next correction

After the split-context correction, Chrome reaches `READY`; the first new
failure is a `Thread-6` `UnsatisfiedLinkError` from Chrome's normal
`System.loadLibrary` path:

```
dlopen failed: library "libmonochrome_64.so" not found
```

This is a later, independent boundary.  The Xiaomi package manager reports
Chrome `usesStaticLibraries: com.google.android.trichromelibrary
version:777818033` and exposes the provider through Chrome's
`usesLibraryFiles`.  The exact 203,432,431-byte provider APK contains the
stored `lib/arm64-v8a/libmonochrome_64.so` (193,598,488 bytes).  The immutable
CAS Chrome revision contains only `libchromium_android_linker.so` and
`libelements.so`; therefore the failing filename is neither in a Chrome split
nor in CAS's current native directory.

The on-device API-36 `framework.jar` disassembly establishes the applicable
platform contract.  `LoadedApk.makePaths(ActivityThread, boolean,
ApplicationInfo, List, List)` invokes `appendApkLibPathIfNeeded` for every
APK in `ApplicationInfo.sharedLibraryFiles`.  For an APK, non-null
`primaryCpuAbi`, and target SDK >= 26, that method appends exactly
`<apk>!/lib/<primaryCpuAbi>` to the native library path.  CAS's
`GuestApplicationInfoFactory` already projects the authority-derived provider
APK into `sharedLibraryFiles`, but `GuestRuntimeEnvironment` constructs its
own defining `PathClassLoader` before the platform's ordinary
`LoadedApk.makePaths` result can become its native search path.  Its current
path comes only from `GuestNativeRuntimeProjection.searchPath`, hence omits
the provider entry.

| Source | Method / behavior read | CAS counterpart | Decision |
| --- | --- | --- | --- |
| NBB | `PackageManagerCompat.generateApplicationInfo` sets the virtual package native directory; `IOCore.enableRedirect` maps the guest `/lib` view to that directory. | `GuestApplicationInfoFactory` and `GuestRuntimeEnvironment.prepareNativeBootstrap`. | Preserve package-owned native directory; do not expose a broad Host library root. |
| VA OSS | `VAppManagerService.installPackage` calls `NativeLibraryHelperCompat.copyNativeBinaries`; `VClientImpl.bindApplication` redirects the guest lib directory to the installed private lib root. | `ApkImportManager.extractNativeLibraries` and the defining `GuestClassLoader`. | Keep validated extraction for imported artifacts; do not suppress or fake a linker success. |
| Xiaomi API 36 framework | `LoadedApk.makePaths` / `appendApkLibPathIfNeeded` add provider APK native elements as `apk!/lib/abi`. | CAS projects `sharedLibraryFiles` but does not add the matching native elements to its manually constructed loader. | Add the same archive-native element from the already resolved shared-library projection, preserving order and ABI. |

The correction will be package-neutral and deterministic: derive only from
`GuestSharedLibraryPathResolver.resolvedSharedLibraryFiles`, require a
canonical readable APK and the guest ABI, and append the framework-equivalent
`!/lib/<abi>` entry to the existing CAS native path.  It neither copies nor
names Chrome/Trichrome explicitly, accepts no arbitrary Host directory, and
does not alter the public `ApplicationInfo.nativeLibraryDir` contract.

## Split-loader duplicate native boundary

The first native-success run exposed a second, independent split contract on
the fresh virtual user.  Xiaomi recorded:

```
Shared library "...trichromelibrary.../base.apk!/lib/arm64-v8a/libmonochrome_64.so"
already opened by ClassLoader ...; can't open in ClassLoader ...
```

The two `DexPathList` values were not two Chrome revisions: one was the Guest
base loader and the other was a newly constructed loader whose parent/path
identity differed.  The same process also logged
`cr_BundleUtils: Mismatched ClassLoaders between Activity and context (fixing)`.
This is a class-loader identity failure, not evidence that a second copy of
the Trichrome APK should be accepted or that the linker should be relaxed.

The reference paths were checked before changing CAS:

| Source | Contract read | CAS finding | Decision |
| --- | --- | --- | --- |
| Android API36 `ContextImpl.createContextForSplit` / `LoadedApk.SplitDependencyLoaderImpl` | For `isolatedSplits`, the split context receives `LoadedApk.getSplitClassLoader`; the split loader is constructed with the cached base `mClassLoader` as its parent. | `GuestContext.createContextForSplit` constructed a `PathClassLoader` with the `GuestClassLoader` policy wrapper as parent, while `GuestLoadedApkBridge` correctly publishes the wrapper's real defining `PathClassLoader` to `LoadedApk`. | Use the same real defining loader as the split parent. Keep one cached loader per verified split and retain the Guest-only context boundary. |
| NBB `BActivityThread.handleBindApplication` / `BPackageManager` | The virtual Application and `LoadedApk` are created once; code-bearing contexts must converge on that loader rather than create a second package loader. | The wrapper was only a lookup/policy facade; classes are actually defined by its inner `PathClassLoader`. | Do not add a Chrome special case, duplicate provider extraction, or a linker retry. |
| VA `VClientImpl.bindApplication` / `PackageParserEx.initApplicationInfoBase` | Application/provider setup follows one `LoadedApk`; `sharedLibraryFiles` is projected from the authority/PMS result. | Native provider path was already authority-resolved and loaded successfully. | Change only split parent identity; preserve the existing shared-library/native path projection. |

The targeted correction changes no APK paths or native policy.  It aligns the
parent identity with the platform split-loader contract so Chromium's
class-loader validation does not enter its reflective repair path and Android
does not reject a library as already owned by a different loader.

The follow-up duplicate-load trace showed that parent identity alone was not
enough on API 36.  `LoadedApk` still received the broker snapshot's
`splitDependencies`; its framework split-dependency loader could therefore
construct a second platform loader even though `mClassLoader` had been set to
CAS's defining loader.  This is the exact AOSP branch that explains the second
`DexPathList` and Xiaomi's `already opened by ClassLoader` error.

The NBB/VA bind paths read before this change both create one virtual
`LoadedApk`/Application pair and do not expose a second split loader: NBB's
`BActivityThread.handleBindApplication` and VA's `VClientImpl.bindApplication`
converge on the same application loader before providers and `onCreate`.  CAS
already loads all broker-verified split DEX into its defining loader and has a
Guest-only `createContextForSplit` boundary.  Therefore the bridge now clears
`splitDependencies` only on its private `ApplicationInfo` copy before creating
the platform `LoadedApk`; the immutable broker snapshot, split paths, resource
paths, and explicit Guest split contexts remain intact.  This keeps the
framework-facing projection on one loader and prevents it from reopening a
provider native archive through a second namespace.  No Chrome name, host
directory, linker retry, or trust relaxation is introduced.

## Activity theme failure and next correction

With the provider-native element present, device `nativeloader` reports a
successful load of `libmonochrome_64.so`, and Chromium reports `Successfully
loaded native library`.  The next first-run Activity then fails before a
visible first frame because `SemanticColorUtils` cannot resolve a required
`com.android.chrome` theme attribute.  The focused window remains the CAS
Stub Activity.

This is a generic package-metadata projection defect.  `BinaryXmlManifestParser`
already preserves `<application android:theme>`, and assigns that default while
parsing activities in the same manifest.  `VirtualPackageStateBuilder`,
however, omits `ManifestSet.applicationThemeResId` from the generated
`ApplicationInfo`; a feature split's activity can also have no local explicit
theme.  CAS's `ActivityFieldBridge` then writes an `ActivityInfo.theme` of zero
and has no application-theme fallback.

| Source | Method / behavior read | CAS counterpart | Decision |
| --- | --- | --- | --- |
| NBB | `PackageManagerCompat.generateActivityInfo` starts from the parser's `ActivityInfo` and replaces only virtual paths/identity in its attached `ApplicationInfo`. | `ActivityFieldBridge.guestActivityInfo` and `VirtualPackageStateBuilder.applicationInfoTemplate`. | Preserve the parsed application theme when projecting the virtual `ApplicationInfo`. |
| VA OSS | `AppInstrumentation.callActivityOnCreate` obtains the virtual `ActivityInfo`, fixes the Context, then applies `info.theme` before calling the real callback. | `GuestActivityThreadInstrumentation.callActivityOnCreate` invokes `ActivityFieldBridge.installGuest` before the delegate callback. | Resolve the Guest activity's explicit theme first, then its projected application theme, before the callback. |

The application-theme projection and framework fallback above were implemented,
but device evidence then showed that Chrome's launcher is an `activity-alias`:
`com.google.android.apps.chrome.Main` has no local theme and targets
`org.chromium.chrome.browser.ChromeTabbedActivity`, whose manifest theme is
`@0x7f1506fb`.  The original application also has no `<application android:theme>`.
CAS consequently still logged `GUEST_THEME ... themeResId=0x0`.

| Source | Method / behavior read | CAS counterpart | Decision |
| --- | --- | --- | --- |
| NBB | `ActivityStack.startActivityLocked` launches the `ResolveInfo.activityInfo` returned by virtual PMS. | `VirtualPackageStateBuilder` snapshot feeds the CAS activity transaction. | Project the resolved alias target's theme; do not invent an application default. |
| VA OSS | `VirtualCore.resolveActivityInfo` sees `ActivityInfo.targetActivity`, obtains that target's `ActivityInfo`, and rewrites the launch component to it. | CAS already keeps the alias for route identity but `GuestActivityThreadInstrumentation.activityInstantiationClass` instantiates its target. | Preserve alias identity and `targetActivity`, but use the target activity's theme in the immutable state consumed by `ActivityThread`. |

The correction is package-neutral and only follows a declared in-package alias
chain. Missing/cyclic targets retain the existing launch failure path; no theme,
resource, package, or native-library value is fabricated.

## Graphics-stats and native crash localization

The first visible-frame sample is not an ordinary window-only failure.  On
Xiaomi API 36 the real Guest `FirstRunActivity` reaches an attached surface and
then the guest process exits with `signal 5 (SIGTRAP)` from the Chrome
`libmonochrome_64.so` immediate-crash site.  The CAS native crash recorder
captures the signal before re-raising it; it is evidence, not the cause.  A
native Chrome launch remains foreground and stable on the same device.

The graphics call immediately before the first frame was also captured as
`HardwareRenderer.ProcessInitializer.requestBuffer`.  The corresponding
reference methods are:

| Source | Method / behavior read | CAS counterpart | Decision |
| --- | --- | --- | --- |
| NBB | `IGraphicsStatsProxy.RequestBufferForProcess` replaces the first app package, then invokes the real `graphicsstats` Binder method. | `PrivilegedServicesInvocationInterceptor.graphics` currently throws `VIRTUAL_GRAPHICS_BUFFER_REQUEST_DENIED` for the default Guest profile, and returns a synthetic null when allowed. | Do not globally open the fail-closed profile or fabricate a buffer. A Chrome compatibility profile must use the host-mode pass-through so the common Guest→Host package rewrite reaches the real service. |
| VA OSS | `GraphicsStatsStub` installs `ReplaceCallingPkgMethodProxy("requestBufferForProcess")` and delegates to the Host service. | CAS's `SystemServiceInvocationHandler` applies `IdentityObjectRewriter` after the service-specific decision. | If a profile explicitly selects `HOST`, return pass-through and let the existing identity rewrite perform the same package substitution. |
| CAS native boundary | `NativeCrashRecorder` records fatal signals with an alternate stack and re-raises the original signal. | The recorded SIGTRAP is from Chrome's immediate-crash instruction, not a CAS-generated signal. | Keep the recorder for evidence; temporarily isolate it only if needed to recover the original Crashpad context, then restore the recorder before acceptance. |

## P2-02 closure

The remaining API-36 Xiaomi failure was localized with the device exception
`NoSuchMethodException: com.android.internal.os.IResultReceiver$Stub.asInterface(IBinder)`.
The runtime's hidden Java Stub was not reflectively callable, so the final fix
does not guess a method or swallow the callback. It writes the exact AIDL
`IResultReceiver.send(int, Bundle)` transaction (`FIRST_CALL_TRANSACTION`,
descriptor `com.android.internal.os.IResultReceiver`, `FLAG_ONEWAY`) to the
receiver Binder. This is the same Binder boundary preserved by the NBB/VA
Autofill proxies; neither reference implementation depends on vendor-hidden
`SyncResultReceiver` reflection. The Guest manifest also declares
`USE_BIOMETRIC`, matching the platform permission gate observed in the Chrome
process.

The final build passed the framework/runtime/app Java compilation and APK
assembly. On Xiaomi `25019PNF3C` the frozen Chrome/Trichrome sample passed 3
cold and 3 valid hot launches, reached `FIRST_FRAME_DRAWN`, accepted the
requested `baidu.com` URL, rendered a homepage and a second article, created
and closed a second tab, returned to Host and re-foregrounded Chrome, and
completed a device-side 600-second stability rerun with 20/20 Chrome focus
samples and no ANR/fatal-error markers.

The complete acceptance record is
[`P2-02_ACCEPTANCE_20260910.md`](../evidence/P2-02_ACCEPTANCE_20260910.md),
with the raw stability sample at
[`P2-02_STABILITY_20260910.txt`](../evidence/P2-02_STABILITY_20260910.txt).
P2-02 is therefore closed as `CHROME_BASIC_SMOKE=PASS`; P2-03 and the
P2-90/P2-91 tail tests remain intentionally open.
