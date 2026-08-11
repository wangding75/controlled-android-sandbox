# XH / SX application inventory

## Scope and artifacts

**DECOMPILED:** the only installed reference app on the approved device is the SX host `com.sx.app.debug`; no package named XH was installed. The APK was pulled from `127.0.0.1:16384` into the T53 evidence root and is not committed. It has 28 dex files, `assets/xposed_init`, `libblackbox.so`, and `libpine.so`.

**SOURCE:** `D:\github\all_project\xh\src_restore` contains a reverse-restored Java/UI shell. Its README states that 360 protection stores true business logic in native `libaa.so` / `libbb.so`; the restore is therefore a partial source reference, not a complete XH implementation.

**DECOMPILED:** relevant classes found in the SX APK include:

- `com.sx.app.sandbox.spoof.hook.DingTalkHook`
- `com.sx.app.sandbox.spoof.hook.CameraHook`
- `com.sx.app.sandbox.spoof.hook.LocationHook`
- `com.sx.app.sandbox.spoof.hook.DeviceHook`
- `com.sx.app.sandbox.spoof.hook.NetworkHook`
- `com.lody.virtual.client.ipc.VDingManager`
- `com.lody.virtual.remote.VDingConfig`

**RUNTIME_OBSERVED:** launching `com.sx.app.debug/com.sx.app.ui.SplashActivity` on the approved emulator initialized `BlackBoxSandboxEngine`, `BlackBoxCore`, and service proxies for location, Wi-Fi, telephony, identifiers, connectivity, Activity/ATMS, package manager and related services. The same trace includes a Google measurement metadata exception and some proxy hook warnings; these are not claimed as T53 capability failures. No DingTalk guest camera/location transaction was observed.

## Native and loader dependencies

The XH restore and SX APK both indicate native dependencies for protected business or ART hook behavior. T53 does not reverse or modify license/activation/VMP/Dex2C logic. Native libraries are inventory evidence only; no large decompilation output is stored in Git.

## Result

The reliable migration target is the Android-semantic data-plane contract plus the observed service boundaries. Unproven protected/native details remain explicitly marked as gaps in the capability matrix.

