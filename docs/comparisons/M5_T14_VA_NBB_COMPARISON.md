# M5-T14 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

This comparison uses the vendored read-only snapshots under `ref/upstream`. It evaluates source architecture and
coverage only. VA/NBB execution history is not evidence for Controlled Sandbox.

| Area | VirtualApp reference | NewBlackbox reference | Controlled Sandbox M5-T14 |
|---|---|---|---|
| MediaSession | Media-session service proxy and package/UID rewriting | MediaSession manager proxy in newer service set | Typed active/playback/metadata projection, bounded sessions and explicit adapter boundary |
| MediaRouter | MediaRouter service proxy with legacy API adaptations | MediaRouter proxy with package identity rewriting | Deterministic virtual route, bounded clients and route/volume mutation policy |
| Audio | Broad AudioService package/UID rewrites and version branches | AudioService proxy and fork-specific method coverage | Typed audio route/volume state plus bounded focus ownership; physical routing remains device-gated |
| SMS | ISms proxy surfaces and identity rewriting | SMS coverage varies by fork/version | Separate text/data/multipart policy, quota and no Host carrier invocation in STATIC mode |
| Backup | BackupManager proxy with deterministic responses | Coverage varies by fork | Typed enabled/provisioned/transport state, controlled acknowledgement and restore denial |
| DropBox | DropBoxManager proxy, usually deterministic or delegated | Coverage varies by fork | Tag policy, bounded writes and no Host entries exposed by default |
| State ownership | Mostly service-hook logic and upstream-specific state | Fork-specific managers/proxies | Package-Service-owned, per-package/per-user durable profile with revision-bound Runtime access |
| Failure policy | Often permissive compatibility fallback | Fork-dependent | Explicit BLOCKED/STATIC/HOST modes and Guest launch failure when required hooks are absent |
| Evidence | Mature project/fork execution history | Fork/device history varies | Source and Host evidence only; production remains PARTIAL and device evidence remains 0 |

## Assessment

M5-T14 closes another source-surface gap with VA/NBB for media, messaging and archival services. Controlled Sandbox is
stronger in typed policy, durable scope ownership, quotas and evidence separation. VA/NBB remain ahead in historical
Android-version adaptation and real application/device execution. Controlled Sandbox does not yet match their real
MediaSession/Audio/SMS compatibility because the hidden Binder objects, callbacks, system roles and hardware/carrier
behavior have not been built or tested on Android.

## Remaining gap after M5-T14

- Real MediaSession/MediaRouter object adapters and callback delivery.
- Audio output-device routing, focus arbitration, Bluetooth/cast and OEM audio policy.
- Carrier SMS/MMS, role enforcement, PendingIntent callbacks and SIM storage.
- Backup/restore agents and real transport execution.
- DropBox entry/file-descriptor projection and platform retention behavior.
- Additional VA/NBB service breadth such as Clipboard refinements, Camera/MediaProjection breadth, NFC/USB, printing,
  companion-device and OEM-specific managers.

Device evidence remains 0. No VA/NBB source was modified or incorporated into product modules.
