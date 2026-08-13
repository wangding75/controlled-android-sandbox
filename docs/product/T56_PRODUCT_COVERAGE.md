# T56 product coverage

This file separates product layers. A UI page is not counted as a runtime pass by itself.

| Area | UI_PRESENT | PRODUCT_FLOW_PRESENT | CONTRACT_PRESENT | RUNTIME_PRESENT | RUNTIME_TESTED | ENVIRONMENT_LIMITED | EXTERNAL_PENDING | DEFERRED |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Installed App Discovery | yes | yes | yes | yes | pending device session | no | real device pending | no |
| Installed App Clone | yes | yes | yes | yes | pending device session | no | real device pending | no |
| APK file import | yes | yes | yes | yes | existing package regression | no | no | no |
| Split APK import | yes | yes | yes | yes | fixture/package regression | no | third-party split app pending | no |
| Multi-instance | yes | yes | yes | yes | targeted self-tests; device acceptance pending | no | API32/API36 device pending | no |
| Clear/Delete | yes | yes | yes | yes | targeted self-tests; device acceptance pending | no | launcher/file cleanup pending | no |
| Shortcut lifecycle | yes | yes | yes | yes | emulator/launcher acceptance pending | no | Launcher behavior pending | no |
| App icon/label | yes | yes | yes | yes | archive/icon fallback build-verified | no | no | no |
| F2 Location | yes | yes | yes | yes | profile self-tests | map picker unavailable | map SDK decision | map picker |
| F3 Camera | yes | yes | yes | yes | existing camera contract regression | Camera1 on API36 AVD | real logged-in app session | no |
| F4 Device | yes | yes | yes | yes | profile self-tests | no | real device metadata | no |
| F5 Network/Cell | yes | yes | yes | yes | profile self-tests | no | real device metadata | no |

The code/build gate does not claim API32 or API36 product acceptance without a device session. The
remaining boundary labels are `REAL_DEVICE_VERIFICATION_PENDING`, `REAL_USER_SESSION_REQUIRED`,
`AVD_CAMERA_HAL_LIMITATION`, `DEFERRED_MAP_SDK_DECISION`, and `F6_DEFERRED`.
