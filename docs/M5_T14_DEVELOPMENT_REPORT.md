# M5-T14 development report

## Result

- Source status: PASS
- Production status: PARTIAL — typed media/communication profiles, durable Package-Service authority,
  revision-bound Runtime access, reversible service hooks and bounded Host-side lifecycle state are source-wired;
  real media objects, system UI, physical audio routing, carrier messaging and archival transports remain
  Android/device-dependent
- Android build: BLOCKED by the unavailable locked JDK 17 in the current environment
- Device evidence: 0

## Delivered

1. Added seven typed Parcelable/AIDL contracts for the aggregate media-communication profile and MediaSession,
   MediaRouter, Audio routing, Messaging, Backup and DropBox domains.
2. Added deterministic per-package/per-virtual-user defaults that do not read Host media, route, audio, SMS, backup or
   DropBox state.
3. Added bounded atomic persistence with CRC verification, atomic replacement, corrupt-state quarantine, scope deletion
   and optimistic policy-version checks.
4. Added Package-Service management get/set/reset methods, revision-authorized Runtime retrieval and asynchronous
   observer refresh.
5. Added reversible MediaSession, MediaRouter, SMS, Backup and DropBox Binder hooks. The existing Audio hook now
   consumes the new audio-routing profile while preserving the prior capability-capture path.
6. Added MediaSession playback/metadata projection, bounded session/listener ownership and explicit transport-control
   policy.
7. Added deterministic MediaRouter route identity/volume projection, bounded client ownership and route-change policy.
8. Added Audio mode/route/volume projection and bounded audio-focus ownership with abandon cleanup.
9. Added separate text/data/multipart SMS policy, sliding-window quotas and optional bounded sent-message metadata.
   Static mode never invokes the Host SMS delegate or reports carrier delivery evidence.
10. Added deterministic Backup state/transport projection, controlled backup acknowledgements and fail-closed restore
    and privileged mutations.
11. Added DropBox tag policy, entry count/size limits and explicit platform-entry adapter boundary.
12. Added fail-closed Guest launch readiness for every configured non-HOST media/communication domain.
13. Added Host regressions for durable isolation/version conflict/corrupt quarantine, service projection, quotas,
    cleanup, denial behavior, HOST passthrough and launch readiness.
14. Preserved the frozen 113-category capability matrix and changed no file under `ref/upstream`.

## Reference review

The read-only VA/NBB snapshots were reviewed for MediaSessionManager, MediaRouter, AudioManager, SMS, BackupManager and
DropBoxManager service surfaces. Controlled Sandbox keeps its own typed profile, Package-Service authority, bounded
lifecycle state, fail-closed modes and evidence separation. No reference implementation source is imported into
product modules.

## Deferred to Android execution

- real Binder signatures and hidden object layouts across Android versions and OEM ROMs;
- MediaSession tokens, controllers, callbacks, playback state objects and SystemUI/notification integration;
- MediaRouter route objects, discovery callbacks, physical output selection and Bluetooth/cast behavior;
- AudioManager focus callbacks, device routing, SCO, volume groups, AppOps and OEM audio policy;
- carrier SMS delivery, PendingIntent result callbacks, subscription/SIM behavior and default-SMS role enforcement;
- Backup transport execution, restore agents, scheduling, account/keystore state and data migration;
- DropBox entry objects, file descriptors, system retention policy, permissions and OEM behavior.
