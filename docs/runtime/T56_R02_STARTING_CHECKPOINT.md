# T56-R02 Starting Checkpoint

Captured at session start. Existing uncommitted work is preserved. No reset, checkout, restore, clean, stash, rebase, squash, or amend was performed.

Raw git dumps: `build/t56-r02-checkpoint/` (not for commit).

## Branch / Git

| Field | Value |
| --- | --- |
| Branch | `feature/t56-product-convergence` |
| HEAD | `bfa436f14044c47c51809ae8d95f3c049b9d0fa5` |
| Tree | `ea9fd4f107a48941329e6124b698526143160f46` |
| Origin | `origin/feature/t56-product-convergence` = `bfa436f14044c47c51809ae8d95f3c049b9d0fa5` |
| Subject | `feat: converge XH product surface and instance flows` |
| Worktree | Dirty. 25 modified files (+588 / -86). 3 untracked roots. |

T56-R01 product acceptance is paused. This checkpoint is the R02 starting field.

## Current device

`adb -s 192.168.137.186:39531 get-state` → `device`

| Field | Value |
| --- | --- |
| Serial | `192.168.137.186:39531` |
| Manufacturer / brand | Xiaomi / Xiaomi |
| Model / device | `25019PNF3C` / `xuanyuan` |
| Android / API | 16 / 36 |
| HyperOS | `OS3.0` (`OS3.0.306.0.WOACNXM`) |
| ABI | `arm64-v8a` |

Host-observable packages (read-only):

| Package | State |
| --- | --- |
| `com.instagram.android` | Installed. `436.0.0.41.73` / `384209423`. base + `split_config.xxxhdpi`. |
| `com.quark.browser` | Installed. Host process running. Not modified. |
| `com.alibaba.android.rimet` | Not installed. Will not be installed in R02. |
| `com.google.android.gms` | Installed on this device (`26.30.32`). Must not be synthesized if absent; must not dump full Host GMS data to Guest if present. |
| `com.warden.controlledsandbox.debug` | Installed and running (`pid` 21844 plus `:sandbox_package` / `:sandbox_server`). |

User ADB connection and Host apps were left running. Leftover T56 `apkanalyzer` on `t56-instagram-base.apk` (java 40964 / cmd 23656) was stopped. No `adb reboot`. No `pm clear` / uninstall / force-stop of Host Instagram or Quark.

## Current Instagram blocker

Latest field probe: `build/t56-r01-xiaomi/flash2/instagram-state-probe-logcat.txt` (2026-08-14 13:13).

Observed sequence on Guest slot `guest2`:

1. Linux / ActivityThread process name remains `com.warden.controlledsandbox.debug:guest2`.
2. Guest factory reads `android:appComponentFactory=com.instagram.process.instagram.Ig4aAppComponentFactory`.
3. Application class `com.instagram.app.InstagramAppShell` is instantiated through that factory.
4. Process-identity bridge reports `package=com.instagram.android process=com.instagram.android`.
5. A subset of providers become `PROVIDER_READY` (`SecureFileProvider`, `FileProvider`, `AndroidXAppInitializer`).
6. Runtime logs `GUEST_PREPARED` for `com.instagram.mainactivity.InstagramMainActivity`.
7. Host binds `StubActivity2`. Guest Activity is created later via `StubActivityBase.postGuestCreationIfResumed`.
8. Activity constructor reports `QPLProvider: QuickPerformanceLogger instance wasn't installed in provider`.
9. `InstagramMainActivity.onCreate` throws `RuntimeException: IgSessionManager not initialized`.
10. Runtime records `GUEST_ACTIVITY_CREATE FAILED` / `GUEST_ACTIVITY_FAILED`.

This is G20. `GUEST_PREPARED` is not `LAUNCH_PASS`.

Earlier R01 Instagram failures that already produced uncommitted fixes (not yet independently verified as complete):

- G15 `HOST_PACKAGE_HIDDEN` during Activity create (GMS / hidden Host packages).
- G16 `LEGACY_AND_V2_BOTH_EXIST:app_minidumps` (empty-dir race, not leftover test data).
- G17 `getHistoricalProcessExitReasons` SecurityException then ClassCastException.
- G10/G11/G12 audio and accessibility denials during bootstrap.
- G13 API36 NFC Binder interface conversion.
- G01/G02 activity-alias filter merge.
- G03/G04 split APK revision / base Manifest vs split resources.
- G05/G06 ApplicationInfo metadata / typed resources.
- G18 AppComponentFactory used for Application + Activity only so far.

## Modified files (uncommitted)

```
 M app/src/main/java/com/warden/controlledsandbox/ApkImportManager.java
 M app/src/main/java/com/warden/controlledsandbox/MainActivity.java
 M app/src/main/java/com/warden/controlledsandbox/VirtualPackageStateBuilder.java
 M sandbox-domain/.../BinaryXmlManifestParser.java
 M sandbox-domain/.../ManifestModel.java
 M sandbox-domain/src/testHarness/.../SelfTest.java
 M sandbox-framework/.../FrameworkHooks.java
 M sandbox-framework/.../MediaCommunicationInvocationInterceptor.java
 M sandbox-framework/.../PolicyServicesInvocationInterceptor.java
 M sandbox-framework/.../ReflectiveServiceHook.java
 M sandbox-framework/.../SystemServiceInvocationHandler.java
 M sandbox-framework/.../VirtualPackageMetadata.java
 M sandbox-framework/.../PackageManagerInvocationHandler.java
 M sandbox-framework/.../NfcServiceHook.java
 M sandbox-runtime/.../GuestActivityController.java
 M sandbox-runtime/.../ActivityTaskFrameworkInterceptor.java
 M sandbox-runtime/.../AndroidTaskInfoProjector.java
 M sandbox-runtime/.../GuestApplicationInfoFactory.java
 M sandbox-runtime/.../GuestComponentRuntime.java
 M sandbox-runtime/.../GuestContext.java
 M sandbox-runtime/.../GuestManifestMetadata.java
 M sandbox-runtime/.../GuestProcessIdentityBridge.java
 M sandbox-runtime/.../GuestResourceLoader.java
 M sandbox-runtime/.../GuestRuntimeEnvironment.java
 M sandbox-runtime/.../GuestStorageNameCodec.java
```

## Untracked

| Path | Role |
| --- | --- |
| `sandbox-runtime/.../GuestComponentFactory.java` | New generic factory wrapper (G18). Source, will be committed after audit + self-test. |
| `docs/product/T56_XIAOMI_DEVICE_RESOLUTION.md` | T56-R01 device lock note. Keep; not R02 evidence. |
| `artifacts/m5-device-lab-rd-t56/` | Prior RD lab evidence. Do not commit unless project rules require. |

## Completed but uncommitted repairs (claimed by the working tree; not accepted yet)

These exist in the dirty tree. They are not verified by R02 gates and must not be treated as correct merely because they are written.

- Manifest activity / activity-alias normalization and same-name alias filter merge.
- Split APK package revision / base Manifest vs split resource loading.
- Typed ApplicationInfo metadata projection.
- Package visibility change around `HOST_PACKAGE_HIDDEN`.
- API36 NFC Binder interface conversion (now takes host service context).
- Accessibility / audio query classification tweaks.
- Historical process-exit query handling.
- Guest storage legacy/v2 empty-dir race.
- Clear-data parent-directory protection (needs security re-review).
- AppComponentFactory for Application and Activity.
- Launch failure text in Host UI (`MainActivity.showFailure`).

## Unconfirmed risks

1. Package visibility may have collapsed to “hidden Host package = NameNotFound” instead of a typed policy (`GUEST_OWNED` / `SYSTEM_PROJECTED` / `SYSTEM_DEPENDENCY_PROJECTED` / `HOST_USER_APP_HIDDEN` / `EXPLICITLY_DENIED`).
2. NFC conversion may have become Host `NfcAdapter` passthrough instead of interceptor → policy → controlled Binder projection.
3. Accessibility may still be blanket deny/allow rather than method-level classes.
4. Provider authority collisions may still drop components instead of using a dual index.
5. AppComponentFactory does not yet wrap Service / Receiver / Provider.
6. Launch gate still treats `GUEST_PREPARED` as success.
7. G20 may be a generic ActivityThread bind/Application/provider/factory order deviation, or Instagram-private. Not yet proven either way.
8. Linux process name is still the Host guest slot name; only a virtual process-name overlay is installed. First bindApplication deviation candidate.
9. No new self-tests for most of the 25-file change except a small alias-merge fixture.
10. Working tree is not split into reviewable commits.

## Session actions already taken

- Stopped leftover T56 `apkanalyzer` only.
- Re-verified Xiaomi serial and identity.
- Saved `git status`, `git diff --stat`, `git diff --name-status`, and full `git diff` under `build/t56-r02-checkpoint/`.
- Next required artifact: `docs/runtime/T56_R02_CHANGE_INVENTORY.md`.
