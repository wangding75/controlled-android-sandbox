# P2-02 reference decision — Chrome split-context failure localization

**Status:** `NO_PRODUCT_CHANGE_YET` — 2026-09-10 (Asia/Shanghai)

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

## Required next evidence before code changes

1. Add a package-neutral, debug-scoped observation of the existing
   `createContextForSplit` result and the application/process loader parent
   chain, with no behavior alteration.
2. Exercise it first against the already imported Chrome revision and retain
   the structured receipt plus logcat.
3. Only if that shows a null/invalid split loader contract, make the smallest
   general `GuestContext` split-context change and add a targeted regression
   fixture. It must preserve broker verification, virtual identity and the
   existing no-host-leak boundary.

No production source, native policy, trust setting, timeout, permission, or
host-directory rule has been changed under this decision.
