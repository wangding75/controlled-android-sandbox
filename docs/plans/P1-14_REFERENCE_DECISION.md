# P1-14 Reference decision — WebView multiprocess boundary and GMS scope

Date: 2026-09-09.  This decision is recorded before P1-14 fixture or debug-harness changes.

## Reference methods actually examined

| Source | Method / observed behavior | CAS counterpart | Decision |
| --- | --- | --- | --- |
| NewBlackbox | `BActivityThread.handleBindApplication` (around lines 358–400) derives the virtual `ApplicationInfo`, sets the WebView data-directory suffix on API 28+, then configures the runtime before application creation. | `GuestRuntimeEnvironment.prepare` installs `WebViewProfileManager` before the Guest Application path and calls `GuestContext.configureWebViewProvider`. | Verify the existing order with a real fixture. Do not configure WebView after the Guest starts or mask an already-initialized WebView failure. |
| NewBlackbox | `AppSystemEnv.isOpenPackage` (58–64) treats its listed WebView packages as open/system packages. This is a package-routing allowance, not renderer, storage, or browser success. | `WebViewProviderServiceContract`, `GuestWebViewProviderServiceBridge`, and `GuestContext.appendApi36WebViewAssets` enforce a selected provider and explicit service allowlist. | Retain CAS’s narrower provider/service contract; reject arbitrary provider packages and record the actual failure stage. |
| NewBlackbox | `GmsCore.isSupportGms` (102–108) only checks whether the GMS package resolves; `BActivityThread.createService` has Google-class failure branches that return `null`. | `GmsCompatibilityBoundary.assess` distinguishes visibility/basic Binder boundary from a runnable GMS service runtime. | Package visibility is not an account, token, network, or GMS API success. Keep all full GMS validation deferred to conditional P2-10. |
| VirtualApp OSS | `VClientImpl.bindApplicationNoCheck` (220–348) creates the application, installs providers, then invokes `Application.onCreate`. | `GuestRuntimeEnvironment`’s deterministic profile/provider setup precedes CAS’s application/bootstrap path. | Test that a renderer loss is handled without a duplicate Application bootstrap. Do not transplant VA’s obsolete implementation or infer VA PRO behavior from its README. |

## Android contract and validation boundary

`WebView.setDataDirectorySuffix` is process-global and must be called before the first WebView in
that process.  A profile must therefore be keyed by virtual user, package, and process before a
Guest creates WebView.  `WebViewClient.onRenderProcessGone` is the platform’s observable renderer
death callback; returning `true` permits the embedding app to dispose and create a replacement
WebView.  The probe uses a local `chrome://crash` renderer request only to exercise that callback,
then verifies a new local page response. It makes no claim about arbitrary renderer stability.

The P1-14 fixture is deliberately offline: HTML, JavaScript, navigation, input, localStorage and
cookie state are generated inside the installed fixture.  Its file chooser callback is observed
and cancelled rather than selecting/uploading external content.  This validates the Guest route
and avoids converting a storage capability test into external-file access.  It cannot prove a
commercial site, Chrome’s own browser engine, GMS login, or an account-backed Google API.

## Change boundary

Add only a debug-command route and an exported test-fixture Activity with deterministic markers.
The Activity accepts only a constrained offline token and has no production capability; exporting
it permits the same page/input control to be run once as a native direct-install baseline. It will:

1. create a WebView after Guest bootstrap; prove JavaScript callback, DOM input value, local
   navigation and a file-chooser callback;
2. write a user-specific cookie/localStorage token and read it back, allowing the runner to
   compare user 0 and user 1 without assuming host storage paths;
3. request an intentional renderer crash, wait for the actual callback, rebuild the WebView and
   prove JavaScript still responds; and
4. report the existing `GmsCompatibilityBoundary` assessment without changing its policy.

No production provider allowlist, profile policy, permission, identity, timeout, GMS package
projection, retry budget, or renderer failure semantics may be broadened. A failed WebView launch
or missing provider asset remains a failure with its real phase recorded.
