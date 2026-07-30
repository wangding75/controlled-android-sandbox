# M5-T15 development report

## Result

- Source status: PASS
- Production status: PARTIAL — typed peripheral profiles, durable Package-Service authority, revision-bound Runtime
  access, reversible service hooks and bounded Host-side lifecycle state are source-wired; physical peripherals,
  system capture UI, Camera Pipeline and OEM Binder object compatibility remain Android/device-dependent
- Android build: BLOCKED by the unavailable locked JDK 17 in the current environment
- Device evidence: 0

## Delivered

1. Added eight typed Parcelable/AIDL contracts for the aggregate peripheral-services profile and NFC, USB, Printing,
   Companion Device, MediaProjection, Camera and generic OEM system-service domains.
2. Added deterministic per-package/per-virtual-user defaults that do not read Host peripheral, printer, capture,
   camera or OEM-service state.
3. Added bounded atomic persistence with CRC verification, atomic replacement, corrupt-state quarantine, scope deletion
   and optimistic policy-version checks.
4. Added Package-Service management get/set/reset methods, revision-authorized Runtime retrieval and asynchronous
   observer refresh.
5. Added reversible NFC, USB, PrintManager, CompanionDeviceManager, MediaProjectionManager and configured OEM Binder
   hooks. The existing Camera hook now consumes the new camera profile.
6. Added deterministic NFC adapter/tag projection, bounded reader ownership and tag-operation quota.
7. Added USB capability/permission/function projection and bounded approved-device ownership.
8. Added print-service/default-printer projection and bounded virtual print-job ownership.
9. Added dynamic process-local companion association state, independent disassociation classification and bounded
   presence observers.
10. Added MediaProjection availability/metrics policy, consent adapter boundary and bounded session ownership.
11. Added camera catalogue/front/torch projection, protected open/torch policy and bounded camera sessions.
12. Added generic OEM query/mutation/session policy with transactional all-or-nothing service-hook installation.
13. Added fail-closed Guest launch readiness for every configured non-HOST peripheral domain.
14. Added Host regressions for durable isolation/version conflict/corrupt quarantine, projection, quotas, cleanup,
    denied Host identities, HOST passthrough and launch readiness.
15. Split common peripheral value conversion from the main interceptor; the production interceptor remains below 500
    lines.
16. Preserved the frozen 113-category capability matrix and changed no file under `ref/upstream`.

## Reference review

The read-only VA/NBB snapshots were reviewed for service-manager registration, NFC reflection and OEM service patterns.
The vendored snapshots contain limited explicit coverage for several modern peripheral managers, which reinforces the
need for a repository-owned typed policy rather than claiming parity from hook counts. No reference implementation
source is imported into product modules.

## Deferred to Android execution

- real Binder signatures and hidden object layouts across Android versions and OEM ROMs;
- NFC Tag, NDEF, HCE, Secure Element, reader callbacks and physical radio behavior;
- USB device/accessory objects, file descriptors, endpoints and transfer APIs;
- PrintDocumentAdapter, PrintJobInfo, RemotePrintService and system print UI;
- Companion Device discovery, association UI, presence callbacks and background privileges;
- MediaProjection consent UI, VirtualDisplay, Surface, audio capture and SystemUI indicators;
- CameraCharacteristics, CameraDevice/Session callbacks, Surface routing, Camera HAL and privacy indicators;
- OEM service descriptors, custom parcelables, permissions, SELinux and device-specific behavior.
