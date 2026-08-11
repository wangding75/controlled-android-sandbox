# T53 runtime evidence index

Evidence root: `D:\controlled-android-sandbox-evidence\T53-20260812-014246\`

All emulator actions in this index used `adb -s 127.0.0.1:16384`. The `adb devices -l` baseline also records other connected endpoints, but none of those endpoints was used for install, launch, logcat, dumpsys, screenshot or acceptance.

## Baseline and audit

- `baseline/`: branch, HEAD/tree/origin, T51 tag, device SDK/ABI, baseline commands and final package information.
- `xh-source/`: selected XH source inventory, source searches and restored implementation excerpts.
- `xh-reverse/`: pulled SX/DingTalk APKs, framework jars, dex inventories, manifests, package dumps, and `VDingManager`/`VDingConfig` smali.
- `xh-runtime/`: SX launch/runtime traces, controlled fixture imports, standard API traces, and build receipts.

## Generic location/device/network

- `location/configure-location-result.json` and `configure-location-result-2.json`: explicit user0/user1 profile configuration.
- `device/instance-isolation-cell-final.log`: final user1 isolation trace; standard Location callback values `35.6762,139.6503`, isolated `u1` file root, Android ID `bd7e70e7cebc035d`, IMEI `962168333662840`, one projected LTE cell, SSID `ControlledSandbox-u1`, BSSID `02:5D:67:9C:C4:7A`, and repeated callbacks.
- `xh-runtime/fixture-controlled-location-manager-2.log`: user0 standard Location API fields and callback trace.
- `xh-runtime/fixture-controlled-telephony-manager.log` and `fixture-cell-wifi-fix.log`: user0 controlled telephony, LTE cell, Wi-Fi and scan values.
- `baseline/device-info-final.txt`: final emulator target, SDK 32, ABI list and product package information.

## Camera

- `camera/fixtures/landscape.png`: SHA-256 `258C57DBA560BE1F67944CDD967A2B2803160824958AF983000D86B01682AE8F`.
- `camera/fixtures/portrait.png`: SHA-256 `7A3DF3BCDE0E22A909F25ED4221A41903D4F8196513906EDE1BE3C1EA47213F1`.
- `camera/fixtures/T53-short.mp4`: SHA-256 `53344945948B0EA3850B9F1F7CF11190428B55194E688502B6B9C3BDC9151495`.
- `camera/camera2-image-final.log`: Camera2 image preview/capture, source frame and result evidence.
- `camera/camera2-image-final-result.jpg`: decoded 1280x720; SHA-256 `CDF65112DC6A41A24C73C7ED3BF2DFEA218C800DF16F7E3FB54015B387673CA6`.
- `camera/camera2-image-final-screen.png`: visual screenshot of the image fixture path.
- `camera/camera2-video-retest.log`: Camera2 video frame/preview/capture evidence.
- `camera/camera-capture-video-result.jpg`: decoded 1080x1920; SHA-256 `90A131D8132E0697F233B7F70D2B0E7F45760CF55BC10D5F23BC34B5BBBC113D`.
- `camera/camera1-host-boundary-final.log`: native Camera1 route, host AppOps revocation and missing substituted result. This is the open P1 blocker.
- `camera/t53-camera2-final-after-teardown-fix.log`: final-code Camera2 image, Location, Wi-Fi, cell, device and capture trace; source hash `258c57db...1682ae8f`, result hash `cdf65112...673ca6`, decoded 1280x720.
- `camera/t53-camera1-final-after-teardown-fix.log`: final-code Camera1 native route and AppOps boundary; no substituted result, confirming the same generic P1 blocker.
- `camera/camera-imagewriter-sigabrt.log` and `camera/camera2-safe-retest.log`: retained negative experiments proving unsafe fake Image/ImageWriter paths were removed.

## DingTalk and regression

- `dingtalk/t53-dingtalk-final-10x-results.json` and `t53-dingtalk-final-10x-logcat.txt`: final-code profile plus 10/10 launch and 10/10 stop, normal generation recovery, and zero target FATAL/ANR matches.
- `quark/t53-quark-final-results.json` and `t53-quark-final-logcat.txt`: final-code import/launch/stop 3/3 PASS and zero target FATAL/ANR matches.
- `stage-a/t53-stage-a-results-teardown-fix-final.json` and `t53-stage-a-logcat-teardown-fix-final.txt`: final-code 16/16 PASS and zero target FATAL/ANR matches.
- `global-review/`: final source search, diff checks and review receipts.
- `backup/`: final ZIP, bundle, hashes, restore script and restore verification after final commit.

## Reports

- `XH_DINGTALK_ARCHITECTURE.md`
- `XH_CAPABILITY_INVENTORY.md`
- `reverse-engineering/XH_APP_INVENTORY.md`
- `reverse-engineering/XH_DINGTALK_CALLGRAPH.md`
- `reverse-engineering/XH_CAMERA_IMPLEMENTATION_REPORT.md`
- `reverse-engineering/XH_LOCATION_IMPLEMENTATION_REPORT.md`
- `reverse-engineering/XH_DEVICE_IMPLEMENTATION_REPORT.md`
- `reverse-engineering/XH_NETWORK_IMPLEMENTATION_REPORT.md`
- `XH_SANDBOX_CAPABILITY_MATRIX.md`
- `DINGTALK_SPECIALIZATION_REPORT.md`
- `T53_GLOBAL_REVIEW.md`

## Final-code source/build receipts

- `global-review/architecture-check-final.txt`: source package-boundary check PASS.
- `global-review/` plus the repository build receipts: build, diff, package-gate and negative-path review; the static handwritten Android harness remains non-authoritative and is not represented as a product build pass.
