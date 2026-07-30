# M5-T13 development report

## Result

- Source status: PASS
- Production status: PARTIAL — typed policy, durable Package-Service authority, revision-bound Runtime access,
  reversible service hooks and bounded Host-side lifecycle state are source-wired; real privileged policy execution,
  framework callbacks, hardware authentication, SystemUI and power/hardware behavior remain Android/device-dependent
- Android build: BLOCKED by the unavailable locked JDK 17 in the current environment
- Device evidence: 0

## Delivered

1. Added seven typed Parcelable/AIDL contracts for the aggregate policy-services profile and DevicePolicy,
   Accessibility, Autofill, Biometric, Sensor Privacy and Power/Vibrator domains.
2. Added deterministic per-package/per-virtual-user defaults that do not read Host administrator, accessibility,
   biometric, privacy, power or vibration state.
3. Added bounded atomic persistence with CRC verification, atomic replacement, corrupt-state quarantine, scope deletion
   and optimistic policy-version checks.
4. Added Package-Service management get/set/reset methods, revision-authorized Runtime retrieval and asynchronous
   observer refresh.
5. Added reversible DevicePolicy, Accessibility, Autofill, Biometric/Fingerprint, Sensor Privacy, Power and Vibrator
   framework service hooks.
6. Added DevicePolicy query projection and fail-closed denial of privileged administrator, password, wipe, reboot and
   ownership mutations outside `HOST`.
7. Added bounded Accessibility client ownership, configured service/state projection and deterministic timeout policy.
8. Added Autofill Session-ID ownership, quota, timeout, finish/cancel cleanup and unsupported-operation denial.
9. Added Biometric/Fingerprint capability and availability projection while refusing to fabricate successful hardware
   authentication. Missing Android callback adaptation rolls back the attempt and fails closed.
10. Added global/camera/microphone Sensor Privacy projection and bounded listener ownership.
11. Added bounded WakeLock and vibration ownership, duration enforcement, release/cancel handling and shutdown cleanup.
12. Added fail-closed Guest launch readiness for every configured non-HOST policy service.
13. Added Host regressions for durable isolation/version conflict/corrupt quarantine, service query/mutation behavior,
    quota enforcement, failure rollback, HOST passthrough and launch readiness.
14. Fixed Autofill ownership to use the Session ID returned to callers rather than callback-object identity.
15. Fixed Biometric callback-adapter failure so it cannot leave a ghost authentication lease.
16. Preserved the frozen 113-category capability matrix and changed no file under `ref/upstream`.

## Reference review

The read-only VA/NBB snapshots were reviewed for DevicePolicyManager, AccessibilityManager, AutofillManager,
Fingerprint/Biometric, Sensor Privacy, PowerManager and Vibrator proxy surfaces. Controlled Sandbox keeps its own typed
profile, Package-Service authority, bounded lifecycle state, fail-closed modes and evidence separation. No reference
implementation source is imported into product modules.

## Deferred to Android execution

- real Binder signatures and hidden object layouts across Android versions and OEM ROMs;
- DevicePolicy administrator ownership, password enforcement, wipe/reboot and managed-profile behavior;
- Accessibility service discovery, event callbacks, window content and SystemUI interaction;
- Autofill Session/UI, FillRequest/SaveRequest, InputMethod/Window integration and service callbacks;
- BiometricPrompt/Fingerprint callback objects, cancellation signals, lockout, Keyguard and TEE-backed authentication;
- Sensor Privacy listeners, permission/AppOps interaction and SystemUI privacy indicators;
- PowerManager WakeLock accounting, Doze/App Standby, battery optimization and process importance;
- VibratorManager composition, audio/haptic routing, hardware effects and OEM behavior.
