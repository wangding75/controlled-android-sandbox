# M5-T8 development report

## Result

- Source status: PASS
- Production status: PARTIAL — six domains are source-wired, while device-identifiers policy/GMS identity,
  BLE event transport and native Sensor event delivery remain device-dependent
- Android build: BLOCKED by the unavailable locked JDK 17 and Android SDK/NDK in the current environment
- Device evidence: 0

## Delivered

1. Added eleven typed Parcelable/AIDL profile contracts covering Location, device identity, bounded multi-SIM
   Telephony, Wi-Fi networks, Bluetooth devices and Sensor descriptions/samples.
2. Added Package-Service-owned deterministic defaults and bounded atomic persistence keyed by package,
   virtual user and immutable revision. Persistence uses size limits, CRC verification, atomic replacement and
   corrupt-file quarantine.
3. Added typed management get/set/reset operations with optimistic profile-version conflict detection, Runtime
   session retrieval and asynchronous observer invalidation.
4. Added reversible Location, Telephony, phone-subscriber, TelephonyRegistry, SubscriptionManager, Wi-Fi,
   Wi-Fi Scanner, Bluetooth, Settings Android ID, Build identity and Sensor catalog installation paths.
5. Added deterministic framework result projection for covered query methods, explicit HOST passthrough,
   BLOCKED failure/neutralization and fail-closed denial of unsupported mutations.
6. Added Guest launch readiness that prevents configured non-HOST domains from starting when a required hook
   is absent, avoiding accidental Host device-identity leakage.
7. Added Host regressions for location result/listener cleanup, HOST passthrough, IMEI/IMSI/operator and
   subscription mapping, Wi-Fi connection/scan identity, Bluetooth adapter/bonded identity, Sensor catalog/sample
   policy, persistence/reload, user isolation, stale-version rejection, reset and corruption quarantine.
8. Kept the frozen 113-category capability matrix unchanged; M5-T8 evidence is recorded separately and does not
   convert source coverage into device compatibility.

## Reference review

The read-only VA/NBB snapshots were reviewed for architecture and surface comparison, including VirtualApp's
location/device persistence and Telephony/Wi-Fi/Bluetooth/Settings proxies, plus NewBlackbox's location,
Telephony, Wi-Fi, Sensor, Android ID and device-identifier proxies. No reference source is imported into product
modules.

## Deferred to Android execution

- actual Android framework object construction and hidden-field writes;
- Settings provider cache and Build-field mutability by API/OEM;
- TelephonyRegistry, SubscriptionManager and Wi-Fi Scanner Binder signatures;
- periodic Location/GNSS callbacks and PendingIntent delivery;
- BLE scanner callback delivery;
- `SystemSensorManager` native queue injection and real `SensorEvent` construction;
- Android permission/AppOps interactions and all device compatibility/stability claims.
