# T57-R03-P4 comparison — CAS vs VirtualApp and NewBlackbox

This is a source comparison of the vendored read-only references under `ref/upstream`. It is not
runtime evidence for VA, NBB, or VA PRO.

| Area | VirtualApp reference | NewBlackbox reference | CAS P4 result |
|---|---|---|---|
| Service substrate | `VActivityManagerService`, `VPackageManagerService`, mirror `ServiceManager` and typed service stubs | `BinderHook`, `BPackageManagerService`, fake framework/service proxies and a service registry | One `SystemServiceInvocationHandler` + `BinderInterceptionFoundation` boundary with semantic catalog/adapter; service-specific policy remains behind it |
| Activity/package | Central server implementations and package cache | Central `BPackageManagerService` with component resolver | Virtual package universe, Activity pair identity proxy, revision/session fences; complete device parity remains partial |
| Account | `VAccountManagerService` has account persistence, authenticator cache/session and response callbacks | `BAccountManagerService` has per-user account store, authenticator cache/session and callbacks | Guest-scoped durable account/token/visibility state, callback completion and explicit authenticator deferral; real authenticator service binding remains deferred |
| WebView | `WebViewFactory`/`IWebViewUpdateService` mirrors and legacy provider handling | `WebViewProxy`, `WebViewFactoryProxy`, update-service proxy | Allowlisted provider/component projection, per Guest/user/process suffix, renderer ownership and Guest cookie/web-storage/file-chooser roots; Chromium device execution remains unverified |
| GMS | VA account/GMS support is coupled to project-specific compatibility helpers | NBB has `GmsCore`, Google account and broker proxies | Package visibility + GMS/GSF identity boundary only; no fabricated full Play-services success; `DEFERRED_GMS_RUNTIME` is explicit |
| Callback/lifecycle | Individual service implementations own callback/session behavior | Individual proxies and service implementations own lifecycle | Returned Binder/callback wrapping and death fencing are centralized; Account/WebView/network/capability ownership is explicit in adapters/state |
| Permission/attribution | Reference implementations primarily rely on virtual server UID/user context and API-specific wrappers | Similar per-service fake/proxy approach with broad framework coverage | AppOps package/uid/opPackageName/AttributionSource validation and chain projection share the Binder semantic adapter |

## Judgment

VA/NBB remain stronger references for accumulated Android transaction signatures, authenticator
sessions, WebView/Chromium quirks, GMS/OEM integrations and real application history. CAS is
stronger in explicit ownership, revision/generation fences, bounded persistence, fail-closed
identity projection, and honest separation of source evidence from device evidence. The comparison
does not convert a reference hook into `CLOSED` parity.
