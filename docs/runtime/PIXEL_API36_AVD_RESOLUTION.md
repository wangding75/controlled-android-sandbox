# Pixel API36 AVD resolution

Date: 2026-08-12  
Branch scope: `feature/ui-oem-compat`

The Android 16 validation target was resolved by enumerating online ADB devices and matching
`adb -s <candidate> emu avd name` to the requested AVD. The serial was not assumed.

| Field | Observed |
|---|---|
| AVD | `Pixel_Android16_API36_GoogleApis_x86_64` |
| Resolved serial | `emulator-5554` |
| Manufacturer / model | Google / `sdk_gphone64_x86_64` |
| Android release / API | 16 / 36 |
| Primary ABI | `x86_64` |
| ABI list | `x86_64,arm64-v8a` |
| Boot state | `sys.boot_completed=1` |
| Fingerprint | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` |

The raw resolution record is [pixel-api36-resolution.json](../../build/t54-r03-evidence/track-b/pixel-api36-resolution.json).
This is Google/Pixel AVD evidence only; it is not Xiaomi HyperOS or physical-device evidence.

