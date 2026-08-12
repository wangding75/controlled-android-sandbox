# Real-device test plan: Xiaomi HyperOS Android 16 / API 36

Target: Xiaomi phone running HyperOS and Android 16 / API 36.  
Approved ADB serial: `127.0.0.1:16384` only.  
Evidence root: `D:\controlled-android-sandbox-evidence\T54-REAL-DEVICE\`.

Every command must be written as `adb -s 127.0.0.1:16384 ...`. Do not install, launch, inspect,
or collect logs from `127.0.0.1:7555` or any other serial. If the approved serial is absent, stop
the device phase and record `REAL_DEVICE_VERIFICATION_PENDING`; do not substitute an emulator or
another device.

## Preflight

```powershell
New-Item -ItemType Directory -Force D:\controlled-android-sandbox-evidence\T54-REAL-DEVICE | Out-Null
adb -s 127.0.0.1:16384 get-state
adb -s 127.0.0.1:16384 shell getprop ro.product.manufacturer
adb -s 127.0.0.1:16384 shell getprop ro.miui.ui.version.name
adb -s 127.0.0.1:16384 shell getprop ro.build.version.sdk
adb -s 127.0.0.1:16384 shell pm path com.sx.app.debug
adb -s 127.0.0.1:16384 shell pm path com.warden.controlledsandbox.debug
```

Save the output before any uninstall. If the old SX package is installed, save version, label,
launcher resolution and the required reference screenshots, then uninstall only:

```powershell
adb -s 127.0.0.1:16384 uninstall com.sx.app.debug
adb -s 127.0.0.1:16384 shell pm path com.sx.app.debug
```

The second package path must still resolve. No DingTalk, Quark, fixture or unrelated package may
be changed by the cleanup.

## Acceptance matrix

| Case | Action | Expected evidence | Result status |
| --- | --- | --- | --- |
| HYPEROS_REGRESSION_CASE_01 | Import `com.android.deskclock` | package record, prepare result, no host package leakage | REAL_DEVICE_VERIFICATION_PENDING |
| HYPEROS_REGRESSION_CASE_02 | Launch / relaunch DeskClock | Guest Activity, task root and transition remain visible | REAL_DEVICE_VERIFICATION_PENDING |
| HYPEROS_REGRESSION_CASE_03 | Start / stop a bound service | service route remains virtual; no exported/UID SecurityException | REAL_DEVICE_VERIFICATION_PENDING |
| HYPEROS_REGRESSION_CASE_04 | Provider access | provider caller/package/UID projection is scoped | REAL_DEVICE_VERIFICATION_PENDING |
| HYPEROS_REGRESSION_CASE_05 | Receiver and JobScheduler | exported flags and job start/stop/finish lifecycle complete | REAL_DEVICE_VERIFICATION_PENDING |
| HYPEROS_REGRESSION_CASE_06 | Stage A fixture64/fixture32, user0/user1 | 16/16 PASS, FATAL/ANR = 0 | PASS_RUNTIME only when run on approved device |
| HYPEROS_REGRESSION_CASE_07 | Quark | prepare, launch, stop, relaunch, stop | PASS_RUNTIME only when run on approved device |
| HYPEROS_REGRESSION_CASE_08 | DingTalk 7.8.10/1178 | 10/10 launch and 10/10 stop; no fake business-session result | PASS_RUNTIME only when run on approved device |
| HYPEROS_REGRESSION_CASE_09 | F2 Location | static coordinate, trajectory contract, reset | PASS_RUNTIME only when run on approved device |
| HYPEROS_REGRESSION_CASE_10 | F3 Camera1 | native connect, NV21 callback, JPEG capture, release/reopen | PASS_RUNTIME only when run on approved device |
| HYPEROS_REGRESSION_CASE_11 | F3 Camera2 | image, video, preview and capture | PASS_RUNTIME only when run on approved device |
| HYPEROS_REGRESSION_CASE_12 | F4 Device | generate, edit, restore one device Profile | PASS_RUNTIME only when run on approved device |
| HYPEROS_REGRESSION_CASE_13 | F5 Wi-Fi/Cell | SSID/BSSID/MAC, scan profile, MCC/MNC/LAC/CID | PASS_RUNTIME only when run on approved device |
| HYPEROS_REGRESSION_CASE_14 | lifecycle | foreground/background, process restart, device reboot | REAL_DEVICE_VERIFICATION_PENDING |

## UI evidence

Capture and retain at minimum:

1. Flash 2 home tab with the old SX package absent;
2. DingTalk App card;
3. Instance settings landing state;
4. F2 Location;
5. F3 Camera;
6. F4 Device;
7. F5 Network/Cell;
8. Developer Diagnostics.

Verify the Launcher label is `闪现2`, the home page has no Runtime/M3/bridge vocabulary, the
settings forms work at 320dp and 360dp widths, and no one-character vertical action button or
obscured long-text field is present.

## Evidence naming

Use stable names such as `hyperos-case-01-deskclock-launch.logcat.txt`,
`ui-home.png`, `ui-camera.png`, and `oem-preflight.txt`. Each result must include timestamp,
serial, package revision SHA-256, API level, and whether the result is `PASS_RUNTIME`,
`PASS_CONTRACT`, or `REAL_DEVICE_VERIFICATION_PENDING`.
