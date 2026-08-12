# T54 Final Capability Matrix

Review date: 2026-08-12

This matrix is the final T54 capability boundary. A simulated or virtual result is not an OEM real-device result. `PENDING` means no claim is made.

| Capability | RD API32 | Pixel API36 | Xiaomi HyperOS API36 | Notes |
|---|---|---|---|---|
| Core Sandbox | PASS_RUNTIME | PASS_RUNTIME | REAL_DEVICE_VERIFICATION_PENDING | API32 M3 formal and API36 generic runtime evidence; no Xiaomi claim |
| Multi-instance | PASS_RUNTIME | PASS_RUNTIME | REAL_DEVICE_VERIFICATION_PENDING | Guest slots, user0/user1 ownership and cleanup validated |
| Activity/Task | PASS_RUNTIME | PASS_RUNTIME | REAL_DEVICE_VERIFICATION_PENDING | Stub window ownership fence and virtual task ledger; API36 host package alias is not exposed as Guest identity |
| Service | PASS_RUNTIME | PASS_RUNTIME | REAL_DEVICE_VERIFICATION_PENDING | Component path validated |
| Receiver | PASS_RUNTIME | PASS_RUNTIME | REAL_DEVICE_VERIFICATION_PENDING | Component path validated |
| Provider | PASS_RUNTIME | PASS_RUNTIME | REAL_DEVICE_VERIFICATION_PENDING | Component path validated |
| JobScheduler | PASS_RUNTIME_TARGETED | PASS_RUNTIME_API36 | REAL_DEVICE_VERIFICATION_PENDING | API32 u0/u1 targeted callback/finish regression; API36 real callback cases 1-5 |
| F2 Location | PASS_RUNTIME | PASS_RUNTIME | REAL_DEVICE_VERIFICATION_PENDING | Static location/profile/trajectory contract, scoped reset and user isolation; map picker is NOT_IMPLEMENTED |
| F3 Camera1 | RUNTIME_VALIDATED | AVD_CAMERA_HAL_LIMITATION | REAL_DEVICE_VERIFICATION_PENDING | API36 result is limited by the AVD camera HAL; it is not a Camera1 runtime failure claim |
| F3 Camera2 | PASS_RUNTIME | PASS_RUNTIME | REAL_DEVICE_VERIFICATION_PENDING | Framework/runtime path, media ownership and instance isolation validated |
| F4 Device | PASS_RUNTIME_CONTRACT | PASS_RUNTIME_CONTRACT | REAL_DEVICE_VERIFICATION_PENDING | Android ID/build/device/SIM contract; API36 physical identifier SecurityException follows platform security policy |
| F5 Wi-Fi/Cell | PASS_RUNTIME_CONTRACT | PASS_RUNTIME_CONTRACT | REAL_DEVICE_VERIFICATION_PENDING | SSID/BSSID/MAC/cell virtual contract and scoped reset; API36 AVD physical HAL is not claimed |
| Quark | 3/3 launch and 3/3 stop, version 10.10.5.1080 | NOT_RUN_NO_LOCAL_APK | REAL_DEVICE_VERIFICATION_PENDING | RD APK is not copied to API36 |
| DingTalk launch | 5/5 Flash2 product UI launch and 5/5 product UI stop | NOT_RUN | REAL_DEVICE_VERIFICATION_PENDING | RD package `com.alibaba.android.rimet`, 7.8.10/1178 |
| DingTalk logged-in business | REAL_USER_SESSION_REQUIRED | REAL_USER_SESSION_REQUIRED | REAL_USER_SESSION_REQUIRED | No logged-in business, Camera business or Location business PASS is claimed |

## Boundary labels

- `PASS_RUNTIME` means the stated virtual/runtime path was exercised and passed in the named environment.
- `PASS_RUNTIME_TARGETED` means the named API32 path was covered by the targeted R04 regression, not by a new full M3 run.
- `PASS_RUNTIME_CONTRACT` means the virtual/framework contract passed; it does not prove physical hardware availability.
- `AVD_CAMERA_HAL_LIMITATION`, `NOT_RUN_NO_LOCAL_APK`, and Xiaomi pending are external execution boundaries.
- `REAL_USER_SESSION_REQUIRED` is a business-session acceptance boundary, not a Runtime failure.
