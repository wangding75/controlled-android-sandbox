# M5-T14 media, communication and archival system-environment source plan

## Objective

Expand the repository-owned system-service layer for MediaSession, MediaRouter, Audio routing/focus, SMS messaging,
Backup and DropBox. This is a source-first iteration: it delivers typed profiles, Package-Service authority,
revision-bound Runtime access, reversible framework hooks, bounded lifecycle state and Host regressions without
claiming Android media UI, physical audio routing, carrier SMS, system backup transport or DropBox device evidence.

## Frozen scope

### MediaSession

- `BLOCKED`, `STATIC` and `HOST` modes.
- Controlled active-state, playback-state, position and metadata projection.
- Bounded session/listener ownership with explicit release and Guest shutdown cleanup.
- Session creation and transport controls allowed only by profile policy.
- Version-specific platform session objects fail closed at the Android adapter boundary.

### MediaRouter and audio routing

- Deterministic selected-route identity, route type and volume projection.
- Bounded router-client ownership and route-change policy.
- Audio mode, ringer mode, speakerphone, Bluetooth SCO, microphone mute and music-volume projection.
- Bounded audio-focus ownership and explicit abandon cleanup.
- Real output-device selection, Bluetooth transport and system media UI remain device-gated.

### SMS and communication

- Controlled default subscription and default SMS package projection.
- Separate text, data and multipart-message policies.
- Sliding-window message quota and optional bounded sent-message metadata.
- ICC/SIM mutations denied outside `HOST`.
- Static mode never invokes the Host SMS service or claims carrier delivery.

### Backup

- Enabled/provisioned/current-transport and transport-list projection.
- Controlled `dataChanged` and backup-request acknowledgement.
- Restore and privileged backup configuration fail closed unless explicitly allowed.
- Real transport execution, restore agents, account/keystore state and system scheduling remain device-gated.

### DropBox

- Tag enablement, bounded entry count and bounded payload size.
- Optional in-memory entry metadata with explicit platform-entry adapter boundary.
- Writes disabled by default and no Host DropBox entries exposed by default.

## State and authority

The aggregate `VirtualMediaCommunicationProfileSnapshot` is keyed by `packageName + virtualUserId`. Runtime access is
bound to the immutable package revision through the existing virtual-system-service session. Updates use optimistic
policy versions, bounded atomic JSON, CRC verification, corrupt-file quarantine and asynchronous observer refresh.
Package or instance deletion removes the matching profile scope.

## Reference-source boundary

`ref/upstream/VirtualApp/**` and `ref/upstream/NewBlackbox/**` remain read-only. Their MediaSession, MediaRouter, Audio,
SMS, Backup and DropBox service surfaces are used only to identify entry points and compatibility pressure. Product
code does not import, compile, package or mechanically translate those sources.

## Acceptance

1. Seven typed Parcelable/AIDL contracts exist for the aggregate and six service domains.
2. Management get/set/reset and Runtime get paths are wired through Package Service.
3. Defaults do not read Host media, route, audio, messaging, backup or DropBox state.
4. MediaSession, MediaRouter, SMS, Backup and DropBox reversible service hooks are source-wired; the existing Audio
   hook consumes the new audio-routing profile.
5. Media sessions/listeners, router clients, audio-focus owners, SMS quota records and DropBox records are bounded.
6. Static SMS handling cannot reach the Host carrier service and does not claim real delivery.
7. Non-HOST configured domains fail closed when required hooks are absent.
8. Store, framework and readiness Host tests execute in `tools/static_android_compile.py`.
9. Architecture, clean-room, package boundary and frozen capability-matrix gates remain unchanged.
10. Source status may be PASS; production remains PARTIAL and device evidence remains 0.

## Android/device boundary

The iteration stops before claiming real MediaSession Binder objects, SystemUI/notification controls, physical route
selection, Bluetooth audio transport, audio-focus arbitration, carrier SMS delivery, SIM storage, Android backup
transport/restore agents or DropBox entry objects. Those require the locked JDK 17, Android SDK/NDK build and
Emulator/physical-device evidence.
