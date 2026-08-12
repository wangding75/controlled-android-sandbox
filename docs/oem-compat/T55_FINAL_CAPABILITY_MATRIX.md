# T55 Final Capability Matrix

T55 is evaluated against the frozen T54 baseline and the final local branch
`feature/t55-hardening`. “PASS” means the capability was exercised with the
current local contract/runtime boundary. It does not imply Xiaomi hardware or
an authenticated DingTalk user session.

| Capability | RD API32 | Pixel API36 | Xiaomi HyperOS API36 | Status | Evidence | Remaining limitation |
|---|---|---|---|---|---|---|
| Core Sandbox | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | T55 full Gradle build; M3/API36 stability | Xiaomi device unavailable in this scope |
| Multi-instance | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | `T55-RD-M3-FORMAL-20MIN`; `T55-API36-STABILITY-5MIN-FINAL3` | Real-device validation pending |
| Activity/Task | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | M3 short 10/10; M3 formal 1200 s; API36 cycles | Real-device validation pending |
| Window lifecycle | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | `StubActivityWindowOwnershipSelfTest`; M3 teardown; API36 cycles | Real-device validation pending |
| Service | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | API32 component suite; API36 12/12 service cycles | Real-device validation pending |
| Receiver | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | API32 component suite; API36 12/12 receiver cycles | Real-device validation pending |
| Provider | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | API32 component suite; API36 12/12 provider cycles | Real-device validation pending |
| JobScheduler | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | API32 self-tests; API36 schedule/start/finish/cancel/stale-callback evidence | Real-device validation pending |
| F2 Location | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | `T55-RD-F2-F5`; API36 generic capability/runtime regression | Xiaomi location semantics not verified |
| F3 Camera1 | PASS | AVD_CAMERA_HAL_LIMITATION | REAL_DEVICE_VERIFICATION_PENDING | PASS within boundary | `T55-RD-CAMERA1-API32`; existing Pixel AVD limitation evidence | Pixel legacy Camera1 HAL limitation |
| F3 Camera2 | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | `T55-RD-CAMERA2-API32`; `T55-API36-CAMERA` | Xiaomi camera validation pending |
| F4 Device | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | `T55-RD-F2-F5`; API36 generic device regression | Xiaomi device profile pending |
| F5 Wi-Fi/Cell | PASS | PASS | REAL_DEVICE_VERIFICATION_PENDING | PASS (local) | `T55-RD-F2-F5`; API36 generic network/device regression | Xiaomi network/radio validation pending |
| Quark | 3/3 launch, 3/3 stop PASS | NOT_RUN_NO_LOCAL_APK | NOT_APPLICABLE_IN_SCOPE | PASS for API32 | `T55-RD-QUARK-3x`; retained version 10.10.5.1080/1080 | No legal local API36 APK |
| DingTalk launch | 5/5 launch, 5/5 stop PASS | NOT_TARGETED_FOR_PRODUCT_PATH | NOT_APPLICABLE_IN_SCOPE | PASS for API32 launch | `T55-RD-DINGTALK-FORMAL-5x`; retained 7.8.10/1178 | API36 logged-out product path was not a T55 acceptance requirement |
| DingTalk logged-in business | REAL_USER_SESSION_REQUIRED | REAL_USER_SESSION_REQUIRED | REAL_USER_SESSION_REQUIRED | DEFERRED | No authenticated account was used | Login, post-login Camera/Location require a real user session |

## Boundary labels

- `REAL_DEVICE_VERIFICATION_PENDING`: Xiaomi HyperOS/API36 hardware validation is outside the local boundary.
- `REAL_USER_SESSION_REQUIRED`: no DingTalk login or post-login business path was performed.
- `AVD_CAMERA_HAL_LIMITATION`: Pixel API36 Camera1 remains limited by the emulator HAL; Camera2 passes.
- `NOT_RUN_NO_LOCAL_APK`: no permitted local Quark API36 APK was available.
- Map picker/SDK selection and F6 Security/Licensing remain deferred product scope.
