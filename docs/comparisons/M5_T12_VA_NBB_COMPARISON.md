# M5-T12 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

This comparison uses the vendored read-only snapshots under `ref/upstream`. It evaluates source architecture and
coverage only. VA/NBB execution history is not evidence for Controlled Sandbox.

| Area | VirtualApp reference | NewBlackbox reference | Controlled Sandbox M5-T12 |
|---|---|---|---|
| WebView data isolation | `WebViewFactory` mirrors and legacy process handling | `WebViewProxy`, `WebViewFactoryProxy`, update-service proxy | Per package/user/process suffix, isolated roots and bounded renderer ownership |
| WebView provider | `IWebViewUpdateService` mirror | `IWebViewUpdateServiceProxy` with modern fork adaptations | Reversible ServiceManager hook and provider/package/wrapper projection |
| Google service support | `GmsSupport` package installation/recognition | `GmsCore`, `GmsProxy`, Google account proxy and app management UI | Stable IDs, availability/API/account allowlists, Secure Settings and optional broker hook |
| Device identifiers | Device identity through legacy proxy surfaces | `IDeviceIdentifiersPolicyProxy` and broader identifiers | Separate device profile plus compatibility-controlled service projection |
| OEM compatibility | Huawei loaded-APK mirrors and version-specific adaptations | Xiaomi detector, MIUI services/settings/security and OEM attribution proxies | Typed vendor/property/service policy, Build projection and descriptor-discovered optional services |
| Detection handling | Mostly indirect filesystem/package/process concealment | `AntiVirtualDetectProxy` and native `AntiDetection.cpp` surfaces | Explicit bounded policy, hidden package/class/path lists, class-loader quota and native `/proc` readiness |
| State ownership | Legacy global/virtual-user services | Fork-specific global state | Package-Service-owned per package + virtual user profile, revision-authorized Runtime access |
| Failure behavior | Compatibility often favors fallback/passthrough | Fork-specific, sometimes permissive fallback | Explicit `BLOCKED`/`STATIC`/`HOST`; required missing hooks block Guest launch |
| Persistence safety | Project/version specific | Fork specific | Bounded atomic JSON, CRC, quarantine and optimistic versioning |
| Android/OEM evidence | Strong historical use but aging platform coverage | Broader recent proxy surface; quality varies by fork | Device evidence remains 0 |

## Current comparative judgment

- M5-T12 closes part of the repository-owned source gap for WebView provider/profile state, deterministic Google/OEM
  identity and policy-driven concealment.
- Controlled Sandbox is stronger in typed aggregate policy, revision authorization, bounded state, deterministic
  failure modes and separating Source PASS from device evidence.
- VA/NBB remain stronger in accumulated Chromium/GMS/OEM transaction signatures, concrete Android wrappers, native
  interception breadth and real third-party application experience.
- WebView/GMS/OEM and detection source wiring does not establish runtime compatibility. Device evidence remains 0.
