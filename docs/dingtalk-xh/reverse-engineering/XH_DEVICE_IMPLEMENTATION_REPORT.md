# XH device identity implementation report

## Evidence

**SOURCE:** XH `DeviceHookManager` declares a Pine/native hook manager for Telephony, Build, Wi-Fi, Bluetooth and Camera but its Java hook bodies are placeholders; the README says native implementation is required.

**DECOMPILED:** SX `DeviceHook` mutates Build fields (`BRAND`, `MODEL`, `MANUFACTURER`, `BOARD`, `SERIAL`, `HARDWARE`) and hooks `Build.getSerial`, Telephony device/IMEI/MEID/IMSI/ICCID/line/operator methods, and Secure Settings. The values are supplied from a `DeviceProfile`.

This is a per-process hook design, not proof of stable persistence or cross-instance isolation.

## Controlled path

`VirtualDeviceServiceDefaults` is the deterministic single source for Android ID, serial, Build profile, installation/advertising identifiers, SIM/IMSI/ICCID/operator, Wi-Fi identity and a matching LTE cell. Values are derived from package + virtual user and persisted in the device profile; no per-call randomization is used.

The Guest identity rewriter and device-service interceptor project these values through normal framework/telephony APIs. Host absolute identity is not copied into the profile.

## Boundary

Real SIM, physical radio and OEM Build/HAL behavior remain real-device items. XH native semantics are not inferred beyond the decompiled method list.

