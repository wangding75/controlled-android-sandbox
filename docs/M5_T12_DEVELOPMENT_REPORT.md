# M5-T12 development report

## Result

- Source status: PASS
- Production status: PARTIAL — typed policy, durable Package-Service authority, revision-bound Runtime access,
  WebView profile/renderer ownership, common WebView/GMS/OEM projections and detection readiness are source-wired;
  concrete Chromium, GMS, OEM and native interception behavior remains Android/device-dependent
- Android build: BLOCKED by the unavailable locked JDK 17 and Android SDK/NDK in the current environment
- Device evidence: 0

## Delivered

1. Added five typed Parcelable/AIDL contracts for the aggregate compatibility profile and WebView, Google services,
   OEM and detection domains.
2. Added deterministic package/user-specific defaults for WebView data suffixes, Advertising ID, App Set ID,
   installation ID, GSF identity, OEM attribution identity and bounded hidden artifact lists without reading Host state.
3. Added bounded atomic persistence with CRC verification, atomic replacement, corrupt-state quarantine, scope deletion
   and optimistic policy-version checks.
4. Added Package-Service management get/set/reset methods, revision-authorized Runtime retrieval and asynchronous
   observer refresh.
5. Added WebView profile isolation before application creation, provider/package projection, mutation denial and a
   bounded renderer registry with deterministic process names, idempotent reservation and shutdown cleanup.
6. Added reversible `ServiceManager` cache hooks for WebView Update and Device Identifiers, plus optional Google service
   broker and descriptor-discovered OEM identifier services.
7. Added WebView provider-response object construction that supports package info and wrapper response objects without
   retaining Host provider identity.
8. Added Google identity projection through Secure Settings and source interception for availability, account types,
   enabled APIs, Advertising ID and App Set ID. Authentication/token paths fail closed when unavailable.
9. Added OEM Build field/property projection and configured identifier-service mediation.
10. Added policy-driven Guest class-loader hiding, explicit PackageManager hidden-package handling and a bounded
    suspicious-query ledger.
11. Added fail-closed launch readiness for WebView Update, Device Identifiers, declared Google service availability,
    configured OEM services and native `/proc` policy.
12. Added Host regressions for durable isolation/version conflict/corrupt quarantine, WebView provider and wrapper
    projection, renderer quotas and cleanup, GMS/OEM identity, class hiding, HOST passthrough and readiness failures.
13. Corrected primitive fallback construction in the compatibility interceptor so long/float/double Binder returns
    cannot receive an incompatible boxed integer.
14. Added the missing main-process management-client get/set/reset methods for compatibility profiles.
15. Preserved the frozen 113-category capability matrix and changed no file under `ref/upstream`.

## Reference review

The read-only VA/NBB snapshots were reviewed for `GmsSupport`, `GmsProxy`, WebView update/factory hooks,
`IDeviceIdentifiersPolicyProxy`, Xiaomi/OEM identifier services and anti-detection surfaces. Controlled Sandbox keeps
its own typed profile, Package-Service authority, fail-closed modes, bounded renderer/detection state and evidence
separation. No reference implementation source is imported into product modules.

## Deferred to Android execution

- real `IWebViewUpdateService`, provider-response, `WebViewFactory` and renderer-process behavior across API versions;
- Chromium sandboxed-process identity, zygote/renderer callbacks, crash recovery, Safe Browsing and multiprocess limits;
- real GMS broker transaction layouts, Accounts, Advertising ID service, App Set service and token lifecycle;
- OEM-specific service names/descriptors and Xiaomi/Huawei/Oppo/Vivo/OnePlus property/service behavior;
- native debugger/root/stack/path interception beyond the existing `/proc` policy readiness requirement;
- hidden API, SELinux, ART/linker behavior and third-party anti-virtualization compatibility.
