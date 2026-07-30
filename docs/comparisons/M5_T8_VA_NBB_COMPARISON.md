# M5-T8 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

The comparison uses the vendored, read-only snapshots under `ref/upstream`. It compares source capability and
architecture only. VA/NBB historical Android execution does not become evidence for Controlled Sandbox.

| Area | VirtualApp reference | NewBlackbox reference | Controlled Sandbox M5-T8 |
|---|---|---|---|
| Profile ownership | Virtual location/device services with project-specific persistence | Location and device-id services/proxies vary by fork | One typed aggregate per package + virtual user + revision, owned by Package Service |
| Policy modes | Mock-location and proxy-specific switches | Proxy-specific replacement/fake behavior | Explicit `BLOCKED` / `STATIC` / `HOST` for every domain |
| Location | Mature LocationManager proxy, mock location, GPS/NMEA helpers | Location service plus last/update/GNSS/provider hooks | Static provider/result, listener lease, GNSS/NMEA source callback, fail-closed unsupported paths |
| Device identity | Persistent virtual device info and Settings provider interception | Android ID, device ID and identifiers-policy proxies | Deterministic Android ID/Build profile, reversible Build and Settings projection; identifiers-policy/GMS deferred |
| Telephony | Telephony and registry method proxies | Telephony and registry proxies | IMEI/MEID/IMSI/ICCID, operator/country/network state, multi-SIM and registry/subscription hook paths |
| SubscriptionManager | Branch/API dependent | Branch/API dependent | Active subscription list, SubId/slot/default-data mappings with reflective object projection |
| Wi-Fi | Connection/scan replacement and package rewriting | Wi-Fi/Wi-Fi Scanner proxies | Connection, scan, DHCP, enabled state and MAC projection; configuration mutations denied |
| Bluetooth | Adapter/address proxy coverage | Coverage varies by fork | Adapter, bonded/scan metadata and remote lookup; real BLE callback/connection transport deferred |
| Sensor | Limited or branch-specific | `ISystemSensorManagerProxy` and sensor identity handling | Typed catalog and deterministic samples; catalog source-wired, native Sensor event queue still partial |
| Persistence safety | Legacy/project-specific | Fork-specific | Bounded atomic state, CRC, quarantine, optimistic versioning and revision cleanup |
| Host leakage control | Broad historical hook coverage | Broad hook set in active forks | Required-hook readiness blocks Guest startup for configured non-HOST domains |
| Android/OEM evidence | Strong historical execution but old branch constraints | More recent fork execution, fork quality varies | Device evidence remains 0 |

## Current comparative judgment

- Controlled Sandbox is stronger in typed contracts, explicit policy modes, revision binding, optimistic updates,
  persistence quarantine and source/device evidence separation.
- VA/NBB remain stronger in accumulated Android-version/OEM execution experience and in breadth of low-level
  compatibility workarounds.
- M5-T8 closes a major source-surface gap, but it does not establish parity for hidden APIs, GNSS timing, BLE,
  GMS/device-identifiers policy or Sensor native event delivery.
