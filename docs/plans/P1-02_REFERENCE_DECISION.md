# P1-02 Reference Decision — Fixture Library and Pre-change Protocol

Status: `SOURCE_ANALYZED`; this record is written before the P1-02 fixture change.

## Reference observations and decision

| Reference | Observed execution behavior | CAS comparison | Adopted for P1-02 |
|---|---|---|---|
| NBB `BActivityThread.handleBindApplication` (`BActivityThread.java:358`) | Obtains virtual `PackageInfo`/`ApplicationInfo`, creates package context and `LoadedApk`, configures runtime/path redirects, then constructs application. | CAS `GuestRuntimeEnvironment.java:603` likewise publishes a `GuestLoadedApkBridge` before application construction. | Test package/class/resource/asset projection as an observable contract; do not copy NBB reflection or redirects. |
| VA `PackageParserEx` (`PackageParserEx.java:212`) | For `dependSystem`, queries an unhooked host PM with `GET_SHARED_LIBRARY_FILES`, then projects `sharedLibraryFiles`. | CAS has an explicit shared-library resolver and host-PM bridge. | Create an independent ordinary-package projection fixture first. It measures class/resource/asset visibility without claiming Android system shared-library support. |
| NBB `IActivityManagerProxy.GetContentProvider` (`…:132`) | Separates system authorities from virtual authorities, resolves virtual provider, starts/acquires its process, and returns null if no provider binder exists. | CAS `GuestContentProviderFrameworkInterceptor.java:111` routes an allow-listed host provider or throws an explicit unvirtualized-authority error; it also serializes provider initialization. | Fixture must distinguish successful reentrant provider transport from absent-provider behavior. No default success and no swallowed exception. |
| VA `MethodProxies.BindService` (`…:850`) and `VActivityManagerService.startProcessIfNeedLocked` (`…:750`) | Binds a service through a declared component and process record; reuses only a live Binder. | CAS `GuestIntentResolver.java:59` resolves virtual service first, then eligible host service, otherwise records a resolution failure. | Use an exported remote-process fixture service with a visible provider-side PID marker; test the missing-service branch separately. |
| VA `MethodProxies.QueryIntentServices` (`…:439`) and NBB `IPackageManagerProxy.ResolveService` (`…:107`) | Query/resolve virtual result first; combine/fall back only where the reference permits it. | CAS has virtual-first service resolution. | Require explicit `null` for a nonexistent component/authority in the native baseline. Do not manufacture a `ResolveInfo` or provider holder. |
| NBB `IOCore.enableRedirect` (`…:107`) and VA `IOUniformer.cpp` (`…:639`) | NBB redirects guest paths before app use; old VA hooks linker symbols by API era. | CAS native behavior is separately owned by `GuestRuntimeEnvironment` and native modules. | Do not adopt either native mechanism. P1-02 dynamic coverage is existing split loading only; the new fixture contains no linker hook or native bypass. |

## CAS failure path → fixture observation

`VirtualPackageStateBuilder` and guest package state publish package metadata → `GuestRuntimeEnvironment` creates the guest loader/application → `GuestIntentResolver` and `GuestContentProviderFrameworkInterceptor` resolve component/provider paths. The fixture will make each outcome visible using deterministic marker strings and explicit return checks:

1. provider package context loads a declared class, string resource, and asset;
2. an exported remote service starts in its declared remote process;
3. a provider's outer call re-enters its inner call and returns the inner value;
4. nonexistent service and authority each return the platform's absence value (`null`) in a native direct-install baseline.

## Rejected alternatives

- No copying of NBB/VA implementation or reflection signatures.
- No use of VA PRO README/changelog as executable reference.
- No catch-all success, fabricated package metadata, fake permission grant, synthetic callback, additional timeout, or relaunch retry.
- No claim that a regular application-package projection is Android's privileged `sharedLibraryFiles` feature. That specific CAS behavior remains for P1-03.

The fixture is application-agnostic and is intended to be run both natively and through CAS in later tasks.
