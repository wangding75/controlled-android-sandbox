# M5-T12 WebView, Google services, OEM and detection source plan

## Objective

Expand the repository-owned compatibility layer for WebView profile/renderer isolation, Google-service identity,
OEM identity/property surfaces and bounded virtual-environment detection controls. This iteration is source-first:
it must produce typed policy, Package-Service authority, revision-bound Runtime access, reversible framework hooks,
fail-closed launch readiness and Host regressions without claiming Android/GMS/OEM execution evidence.

## Frozen scope

### WebView

- `BLOCKED`, `STATIC` and `HOST` policy modes.
- Stable provider package/version projection through the WebView update service.
- Per-package, per-virtual-user and per-process data-directory suffixes.
- Bounded renderer ownership, deterministic renderer names, duplicate reservation idempotence and shutdown cleanup.
- Multiprocess, safe-browsing and debugging policy fields.
- Mutation denial when the profile is not `HOST`.

### Google services identity

- Stable Advertising ID, Limit Ad Tracking, App Set ID, GSF ID and installation ID.
- Visible Google account-type and enabled-API allowlists.
- Google Play services availability policy.
- Secure Settings projection for supported identity keys.
- Optional `gms` service-broker hook; when availability is declared, a missing broker blocks launch.
- Authentication/token calls fail closed when Google services are unavailable. No fabricated successful token is allowed.

### OEM compatibility

- Virtual vendor, skin, attribution identifier and bounded property key/value projection.
- Build-field projection for common OEM-visible fields.
- Configured OEM Binder services installed by runtime interface descriptor.
- Explicit blocked OEM package list and service availability list.
- Missing configured OEM identifier services block launch; an empty list makes the hook optional.

### Detection governance

- Explicit host-package hiding and bounded hidden package/class/path lists.
- Guest class-loader denial for configured detection classes with a suspicious-query quota.
- Package-manager denial for the Host and configured hidden package identities.
- Native `/proc` sanitization is required when the non-HOST policy enables it.
- Debugger masking, root-artifact masking and stack sanitization remain policy contracts until Android/native
  interception can be compiled and exercised.

## State and authority

The aggregate `VirtualCompatibilityProfileSnapshot` is keyed by `packageName + virtualUserId`. Runtime access is
bound to the immutable package revision through the existing virtual-system-service session. Updates use optimistic
policy versions, atomic bounded JSON, CRC verification, corrupt-file quarantine and asynchronous observer refresh.
Package or instance deletion removes the matching compatibility scope.

## Reference-source boundary

`ref/upstream/VirtualApp/**` and `ref/upstream/NewBlackbox/**` remain read-only. Their GMS support, WebView update,
device-identifiers, Xiaomi/OEM and detection proxy surfaces are used only to identify compatibility pressure and
service entry points. Product code does not import, compile, package or mechanically translate those sources.

## Acceptance

1. Five typed Parcelable/AIDL contracts exist for the aggregate and four domains.
2. Management get/set/reset and Runtime get paths are wired through Package Service.
3. Defaults never read Host WebView/GMS/OEM identity.
4. WebView update, device-identifiers, optional GMS and configured OEM services have reversible source hooks.
5. Renderer leases and detection class queries are bounded and cleaned up.
6. Non-HOST configured domains fail closed when required hooks/native policy are absent.
7. Store, framework and readiness Host tests execute in `tools/static_android_compile.py`.
8. Architecture, clean-room, package boundary and frozen capability-matrix gates remain unchanged.
9. Source status may be PASS; production remains PARTIAL and device evidence remains 0.

## Android/device boundary

The iteration stops before claiming real Chromium renderer launch, `IWebViewUpdateService` wrapper compatibility,
GMS broker transactions, Google account/token behavior, OEM Binder descriptors, native anti-detection interception,
hidden API compatibility, SELinux behavior or third-party application compatibility. Those require the locked JDK 17,
Android SDK/NDK build and Emulator/physical-device evidence.
