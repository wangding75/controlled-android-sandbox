# M5-T15 peripheral and external system-services source plan

## Objective

Expand the repository-owned system-service layer for NFC, USB, Printing, Companion Device, MediaProjection, Camera and
configured OEM Binder services. This is a source-first iteration: it delivers typed profiles, Package-Service
authority, revision-bound Runtime access, reversible hooks, bounded lifecycle state and Host regressions without
claiming physical peripheral access, system capture UI, Camera Pipeline or OEM-device evidence.

## Frozen scope

### NFC

- `BLOCKED`, `STATIC` and `HOST` modes.
- Deterministic adapter state, reader-mode policy, card-emulation/NDEF capability and approved tag catalogue.
- Bounded reader sessions and tag-operation quota with explicit release.
- Real tag Binder objects, NDEF records, Secure Element and HCE routing remain device-gated.

### USB

- Host/accessory capability, permission-request policy, approved device/accessory identities and USB functions.
- Bounded virtual open-device ownership with close cleanup.
- USB function mutations, unapproved devices and unknown operations fail closed.
- Real file descriptors, endpoints, bulk/control transfer and accessory transport remain device-gated.

### Printing and Companion Device

- Deterministic print-service/default-printer projection and bounded active print jobs.
- Dynamic process-local companion association state, disassociation policy and bounded presence observers.
- Real PrintDocumentAdapter/RemotePrintService, system picker UI and Companion Device discovery remain device-gated.

### MediaProjection and Camera

- Projection availability, screen/audio policy, consent boundary, virtual metrics and bounded sessions.
- Camera catalogue, front/torch subsets, open/torch policy and bounded camera sessions.
- Consent UI, Surface/VirtualDisplay, CameraCharacteristics, CameraDevice callbacks and Camera HAL remain device-gated.

### OEM system services

- Configured Binder service names with query-prefix allowlist, mutation-prefix denylist and bounded sessions.
- Hook installation is transactional: any configured-service failure rolls back already installed hooks.
- Unknown OEM methods fail closed; descriptors and object adapters remain Android/OEM-gated.

## State and authority

The aggregate `VirtualPeripheralServicesProfileSnapshot` is keyed by `packageName + virtualUserId`. Runtime access is
bound to the immutable package revision through the existing virtual-system-service session. Updates use optimistic
policy versions, bounded atomic JSON, CRC verification, corrupt-file quarantine and asynchronous observer refresh.
Package or instance deletion removes the matching profile scope.

## Reference-source boundary

`ref/upstream/VirtualApp/**` and `ref/upstream/NewBlackbox/**` remain read-only. Their service registration patterns,
NFC reflection surfaces and OEM compatibility hooks are used only to identify compatibility pressure and Android
entry points. Product code does not import, compile, package or mechanically translate those sources.

## Acceptance

1. Eight typed Parcelable/AIDL contracts exist for the aggregate and seven service domains.
2. Management get/set/reset and Runtime get paths are wired through Package Service.
3. Defaults do not read Host NFC, USB, printer, companion, projection, camera or OEM service state.
4. NFC, USB, Printing, Companion Device, MediaProjection and configured OEM hooks are reversible; Camera consumes the
   new profile through the existing camera service hook.
5. Reader, USB, print, presence, projection, camera and OEM sessions are bounded and explicitly releasable.
6. Companion disassociation is classified independently from association and dynamic association state is isolated to
   the Guest process generation.
7. Configured OEM hooks install all-or-nothing and fail closed on partial installation.
8. Non-HOST configured domains fail closed when required hooks are absent.
9. Store, framework and readiness Host tests execute in `tools/static_android_compile.py`.
10. Architecture, clean-room, package boundary and frozen capability-matrix gates remain unchanged.
11. Source status may be PASS; production remains PARTIAL and device evidence remains 0.

## Android/device boundary

The iteration stops before claiming physical NFC/USB transport, printing UI/provider execution, companion discovery,
MediaProjection consent/Surface capture, CameraCharacteristics/CameraDevice behavior or OEM Binder descriptor
compatibility. Those require the locked JDK 17, Android SDK/NDK build and Emulator/physical-device evidence.
