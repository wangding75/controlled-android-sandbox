# M5-T13 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

This comparison uses the vendored read-only snapshots under `ref/upstream`. It evaluates source architecture and
coverage only. VA/NBB execution history is not evidence for Controlled Sandbox.

| Area | VirtualApp reference | NewBlackbox reference | Controlled Sandbox M5-T13 |
|---|---|---|---|
| Device policy | DevicePolicyManager proxy with legacy API adaptations | Device-policy proxy coverage varies by fork | Typed administrator/owner/security projection with privileged mutations denied outside HOST |
| Accessibility | Accessibility service proxy and package/UID rewriting | Accessibility proxy and newer service surfaces | Bounded clients, enabled-state/service projection, timeout and event-dispatch policy |
| Autofill | Limited or version-dependent support | Autofill service proxy in newer forks | Bounded Session-ID lifecycle, service/save state and explicit callback/UI device boundary |
| Biometric/Fingerprint | Fingerprint proxy surfaces | Fingerprint/Biometric proxies and fork-specific adaptations | Deterministic capabilities/outcome policy; no fabricated successful hardware authentication |
| Sensor privacy | Limited on older platform baseline | Newer forks may include privacy-service proxies | Global/camera/microphone state and bounded listener policy |
| Power/WakeLock | PowerManager package/UID rewriting | Power/PowerEx and process-related proxies | Bounded WakeLock ownership, duration, cleanup and fail-closed privileged operations |
| Vibrator | Vibrator service proxy | Vibrator/VibratorManager coverage varies by branch | Bounded vibration ownership, duration and cancel policy |
| State ownership | Legacy global/virtual-user services | Fork-specific global state | Package-Service-owned per package + virtual user profile, revision-authorized Runtime access |
| Failure behavior | Compatibility often favors passthrough | Fork-specific, sometimes permissive fallback | Explicit `BLOCKED`/`STATIC`/`HOST`; missing required hooks block Guest launch |
| Persistence safety | Project/version specific | Fork specific | Bounded atomic JSON, CRC, quarantine and optimistic versioning |
| Android/device evidence | Historical device use but aging platform coverage | Broader recent proxy surface; quality varies by fork | Device evidence remains 0 |

## Current comparative judgment

- M5-T13 closes a substantial source-architecture gap for policy, accessibility, autofill, biometric/privacy and power
  services while retaining deterministic state ownership and failure modes.
- Controlled Sandbox is stronger in typed aggregate policy, revision authorization, bounded lifecycle registries,
  optimistic persistence and separating Source PASS from device evidence.
- VA/NBB remain stronger in accumulated platform signatures, real SystemUI/service callback adaptations, hardware and
  OEM behavior, and third-party application experience.
- Source-wired query projection and lifecycle state do not establish real policy enforcement, authentication or power
  behavior. Device evidence remains 0.
