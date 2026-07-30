# M5-T13 policy, accessibility, autofill, biometric, privacy and power source plan

## Objective

Expand the repository-owned system-policy layer for DevicePolicy, Accessibility, Autofill, Biometric/Fingerprint,
Sensor Privacy, Power/WakeLock and Vibrator services. This iteration is source-first: it delivers typed policy,
Package-Service authority, revision-bound Runtime access, reversible framework hooks, bounded lifecycle state and
Host regressions without claiming Android system-UI, hardware-authenticator or device execution evidence.

## Frozen scope

### Device policy

- `BLOCKED`, `STATIC` and `HOST` modes.
- Controlled administrator, profile-owner and device-owner query projection.
- Camera, screen-capture, encryption and password-policy query projection.
- Device-owner, profile-owner, password-reset, wipe, reboot and other privileged mutations denied outside `HOST`.

### Accessibility

- Enabled, touch-exploration and high-text-contrast state projection.
- Bounded client/listener ownership and deterministic recommended timeout.
- Configured enabled-service projection and explicit event-dispatch policy.
- Service enablement and privileged accessibility mutations denied outside `HOST`.

### Autofill

- Enabled/service/save/augmented-autofill projection.
- Bounded Session-ID ownership, timeout expiry and finish/cancel cleanup.
- Unsupported session restoration and service mutations fail closed.
- Real Android callback and window integration remains device-gated.

### Biometric and fingerprint

- Hardware, enrollment, authenticator and strength projection.
- Deterministic authentication availability and explicit outcome policy.
- Bounded authentication-session ownership with rollback when the Android callback adapter is unavailable.
- No fabricated successful hardware authentication or credential confirmation.

### Sensor privacy

- Global, camera and microphone privacy-state projection.
- Bounded listener ownership and cleanup.
- Privacy mutations allowed only when the virtual profile explicitly permits them.

### Power, WakeLock and Vibrator

- Interactive, power-save, idle and battery-optimization projection.
- Bounded WakeLock ownership, maximum duration, release and generation cleanup.
- Bounded vibration ownership, duration, cancel and shutdown cleanup.
- Reboot, shutdown, forced sleep, unrestricted WakeLock and unsupported hardware operations fail closed.

## State and authority

The aggregate `VirtualPolicyServicesProfileSnapshot` is keyed by `packageName + virtualUserId`. Runtime access is
bound to the immutable package revision through the existing virtual-system-service session. Updates use optimistic
policy versions, atomic bounded JSON, CRC verification, corrupt-file quarantine and asynchronous observer refresh.
Package or instance deletion removes the matching policy scope.

## Reference-source boundary

`ref/upstream/VirtualApp/**` and `ref/upstream/NewBlackbox/**` remain read-only. Their DevicePolicy, Accessibility,
Autofill, Fingerprint/Biometric, Power and Vibrator proxy surfaces are used only to identify service entry points and
compatibility pressure. Product code does not import, compile, package or mechanically translate those sources.

## Acceptance

1. Seven typed Parcelable/AIDL contracts exist for the aggregate and six policy domains.
2. Management get/set/reset and Runtime get paths are wired through Package Service.
3. Defaults do not read Host policy, accessibility, biometric, privacy, power or vibration state.
4. Seven reversible framework service hooks are source-wired.
5. Accessibility listeners, Autofill sessions, Biometric attempts, Sensor Privacy listeners, WakeLocks and vibrations
   are bounded and cleaned up on release, failure or Guest shutdown.
6. Non-HOST configured domains fail closed when required hooks are absent.
7. Store, framework and readiness Host tests execute in `tools/static_android_compile.py`.
8. Architecture, clean-room, package boundary and frozen capability-matrix gates remain unchanged.
9. Source status may be PASS; production remains PARTIAL and device evidence remains 0.

## Android/device boundary

The iteration stops before claiming real DevicePolicy enforcement, Accessibility event delivery, Autofill window/UI
integration, BiometricPrompt/Fingerprint callback compatibility, hardware-backed authentication, Sensor Privacy
SystemUI indicators, real PowerManager accounting, Doze/App Standby enforcement or physical vibration. Those require
the locked JDK 17, Android SDK/NDK build and Emulator/physical-device evidence.
