# M5-T15 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

This comparison uses the vendored read-only snapshots under `ref/upstream`. It evaluates source architecture and
coverage only. VA/NBB execution history is not evidence for Controlled Sandbox.

| Area | VirtualApp reference | NewBlackbox reference | Controlled Sandbox M5-T15 |
|---|---|---|---|
| NFC | Snapshot exposes limited/branch-dependent NFC handling | Includes an `INfcAdapter` reflection surface | Typed adapter/reader/tag policy, bounded sessions and explicit object-adapter boundary |
| USB | Coverage varies by historical branch and Android version | Sparse explicit USB virtualization in the vendored snapshot | Approved-device policy, permission/function projection and bounded opens |
| Printing | Branch-dependent manager proxy coverage | Sparse explicit print-service coverage in the vendored snapshot | Typed print-service/default-printer policy and bounded virtual jobs |
| Companion Device | Limited in older VA generations | Coverage varies by fork/version | Typed associations, dynamic generation-local state and bounded presence observers |
| MediaProjection | Mostly identity/permission compatibility through adjacent services | Fork/version-dependent | Typed capture/consent/metrics policy and bounded projection sessions |
| Camera | Camera service package/UID rewriting and version branches | Camera/OEM-related compatibility varies by fork | Typed catalogue/open/torch policy integrated with existing permission/AppOps gate |
| OEM services | Device/fork-specific hooks | Includes explicit OEM service proxies, including Xiaomi-related surfaces | Configured descriptor discovery, query/mutation policy and transactional hook installation |
| State ownership | Primarily hook-specific and upstream-version-specific | Fork-specific managers/proxies | Package-Service-owned durable profile with revision-bound Runtime access |
| Failure policy | Compatibility-oriented fallback varies by branch | Fork-dependent | Explicit BLOCKED/STATIC/HOST modes and Guest launch failure when required hooks are absent |
| Evidence | Mature historical project evidence | Fork/device history varies | Source and Host evidence only; production remains PARTIAL and device evidence remains 0 |

## Assessment

M5-T15 expands Controlled Sandbox into peripheral and external-system-service areas where the vendored VA/NBB snapshots
are uneven or version-specific. Controlled Sandbox is stronger in typed policy, durable scope ownership, quotas,
transactional hook installation and evidence separation. VA/NBB remain ahead in accumulated Android/OEM execution
experience. Controlled Sandbox does not yet match real peripheral compatibility because hidden Binder objects,
callbacks, system UI and physical hardware paths have not been built or tested on Android.

## Remaining gap after M5-T15

- Real NFC/USB object adapters and physical transport.
- Print framework objects, provider lifecycle and system UI.
- Companion discovery/association UI and presence callbacks.
- MediaProjection consent, Surface/VirtualDisplay and capture pipeline.
- CameraCharacteristics, CameraDevice/Session and HAL integration.
- OEM-specific descriptors, parcelables, permissions and ROM behavior.
- Additional system-service breadth and all real APK/Emulator/device compatibility evidence.

Device evidence remains 0. No VA/NBB source was modified or incorporated into product modules.
