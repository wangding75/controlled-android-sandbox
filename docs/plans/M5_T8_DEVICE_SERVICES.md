# M5-T8 — Location, device identity, Telephony, Wi-Fi, Bluetooth and Sensor virtualization

## Goal

Expand the repository-owned device-service virtualization source surface before Android device execution.
The stage delivers one revision-bound profile per package and virtual user, deterministic defaults,
Package-Service-owned persistence, typed Binder management, framework result projection and fail-closed
Guest startup when a configured non-HOST domain cannot be hooked.

The six bounded domains are:

1. Location: provider state, last/current location, listener registration/removal, GNSS/NMEA callbacks and explicit denial of unsupported mutation/geofence paths.
2. Device identity: Android ID plus reversible `android.os.Build` projection for brand, manufacturer, model, device, product, fingerprint, board, hardware and serial.
3. Telephony: multi-SIM slot/subscription identity, IMEI/MEID/IMSI/ICCID, operator/country/network state, SubscriptionManager queries and mutation denial.
4. Wi-Fi: enabled state, connection identity, scan results, DHCP and factory MAC projection with configuration mutation denial.
5. Bluetooth: adapter identity/state, bonded and discovered device metadata, remote-device lookup and connection/mutation denial.
6. Sensor: virtual catalog, deterministic sample profiles, listener ownership model and bounded source-side callback policy.

## Modes and isolation

Every domain uses one of three explicit modes:

- `BLOCKED`: fail closed or return a neutralized identity where the Android API cannot throw safely.
- `STATIC`: return the persisted virtual profile and never query the Host for covered operations.
- `HOST`: use the Host implementation explicitly.

Profiles are keyed by package name, virtual-user ID and immutable package revision. Updates use optimistic
version checks, survive Package-Service restart and notify active Runtime sessions. Package deletion removes
the matching profile scope.

## Source boundary

`ref/upstream/VirtualApp/**` and `ref/upstream/NewBlackbox/**` are read-only reference snapshots. They are
used to compare capability boundaries, Android service names and known compatibility pressure points.
Product code is independently authored and does not import, compile, package or mechanically translate
reference implementation classes.

## Validation boundary

Host/static evidence proves typed contracts, validation, deterministic generation, persistence, revision and
virtual-user isolation, optimistic concurrency, corrupt-state quarantine, method routing, Host bypass,
fail-closed readiness and callback/lease cleanup.

The following remain Android-execution dependent:

- real Binder field and Settings cache layouts across Android/API/OEM versions;
- hidden constructor/field compatibility for `Location`, `WifiInfo`, `ScanResult`, `SubscriptionInfo`,
  `BluetoothDevice` and `Sensor`;
- real GNSS satellite objects, PendingIntent location delivery and periodic timing;
- device-identifiers policy Binder and Google Advertising ID/GMS behavior;
- TelephonyRegistry and SubscriptionManager signature variants;
- BLE scanner callback delivery and real Bluetooth connection behavior;
- synthetic `SensorEvent` delivery through `SystemSensorManager` native queues;
- all permission, SELinux, hidden-API and OEM runtime behavior.

M5-T8 must stop at this boundary rather than claim device compatibility without a locked Android build and
Emulator/physical-device evidence.
