# XH camera implementation report

Date: 2026-08-12. This report distinguishes recovered source, decompiled
platform/native evidence, runtime observations and inference. The protected
XH implementation is not assumed where only SX legacy code or UI placeholders
are available.

## XH and SX evidence

**SOURCE:** XH `VirtualCameraActivity` contains a configured image path and
preview/result UI plumbing, but `loadVirtualImage`, `initCamera` and the
result payload are restore placeholders. No protected Camera1 native boundary
is exposed by the checked-in XH source.

**DECOMPILED:** SX `CameraHook` installs legacy Java hooks for Camera1
`setPreviewCallback`, `setOneShotPreviewCallback`,
`setPreviewCallbackWithBuffer` and `takePicture`; it also references Camera2
ImageReader/Plane hooks and MP4 frame extraction. Its global media path and
process-global buffers are explicitly classified as incomplete legacy design.
The SX code is not copied into the Sandbox.

**DECOMPILED:** API-32 platform libraries expose the actual Camera1 native
symbols `android::Camera::connect`, `get_native_camera`,
`JNICameraContext::copyAndPost` and `JNICameraContext::release`. The
`copyAndPost` and `release` relocations are present in `libandroid_runtime`.

**RUNTIME_OBSERVED:** the controlled Camera1 fixture entered
`CameraService::connect` through native `libcamera_client`/Binder, opened a
`Camera2ClientBase`, delivered a real NV21 callback and a JPEG callback, and
completed re-open/release after the generic adapter was installed.

**RUNTIME_OBSERVED:** final evidence under `camera1-final/` repeats the same
contract for virtual users 0 and 1. User0 source/callback/capture hashes are
`258C57DB...1682AE8F`, `58104741...3A497949` and
`CDF65112...B387673CA`; user1 uses portrait source
`7A3DF3BC...47213F1`. No CameraService camera revoke was observed.

**RUNTIME_OBSERVED:** no DingTalk camera page was reached without a real user
session, so no DingTalk page screenshot or business-result hash is claimed.

## Preview replacement

The final generic path is:

```text
Camera1 startPreview
  -> native Camera client / CameraService
  -> JNICameraContext::copyAndPost(messageType=0x10)
  -> VirtualCameraCaptureEngine
  -> NV21 bytes
  -> real Camera1 preview callback
```

The fixture uses `SurfaceTexture` for the normal Camera1 preview contract and
`setPreviewCallbackWithBuffer` for the data-plane assertion. It received a
`1280x720`, format-17 NV21 buffer of `1,382,400` bytes from the real callback.
The callback is not replaced by directly writing a fixture file.

## Final capture replacement

The final generic path is:

```text
Camera1 takePicture
  -> native Camera client / CameraService
  -> JNICameraContext::copyAndPost(messageType=0x100)
  -> VirtualCameraCaptureEngine
  -> JPEG bytes derived from the instance-owned source
  -> real Camera.PictureCallback
```

The image source SHA-256 is
`258C57DBA560BE1F67944CDD967A2B2803160824958AF983000D86B01682AE8F`.
The returned JPEG decodes to `1280x720` and is pixel-equivalent to the source
within JPEG compression error. Preview and final capture are separate
message paths and are both observed in the runtime log. The final byte,
dimension and decoded-tolerance calculations are in
`camera1-final/artifacts/equivalence.txt`.

## Native adapter and lifecycle

The generic adapter is in `sandbox-native/native_camera1.cpp` and is installed
by the native hook runtime only when `libandroid_runtime.so` is present. It
uses symbol enumeration and exact PLT/GOT replacement; it does not use
DingTalk package strings. It shares `VirtualCameraProfile`,
`VirtualCameraMediaStore` and `VirtualCameraCaptureEngine` with Camera2.

`JNICameraContext::release` calls the original release first and then removes
the Java weak reference/context mapping. The final run logged
`CONTEXT_RELEASED remaining=0` for both the first session and re-opened
session. This closes the prior stale-context/native-reference P1.

## AppOps and Surface boundary

Before the native adapter, the Java `media.camera` proxy did not see Camera1;
the native CameraService request used the host process identity and MuMu
revoked the camera operation because the package/UID attribution did not
match. The corrected `Camera::connect` boundary makes the CameraService view
consistent with the host UID. Camera access is no longer revoked.

MuMu still emits a `checkAudioOperation` warning from the camera shutter-audio
path. It is not a camera denial and is retained as an Android/MuMu P2 rather
than being suppressed or granted around. Direct vendor Surface/HAL behavior
remains a real-device compatibility boundary; no fake Surface success is
claimed.

## Video

The common media engine supports bounded image/video sources and Camera2
video is runtime-proven. No XH source or runtime evidence proves a protected
XH Camera1 video callback/Surface implementation. SX's MP4 loop is historical
evidence only. The Sandbox therefore claims generic readiness, not an
XH-specific Camera1-video fact.

## Result

The generic Camera1 native path is complete for the verified MuMu contract:
native connect, preview callback, capture callback, source substitution,
release/re-open and instance-bound source ownership. Camera1 is no longer a
T53 blocker. DingTalk-specific code only supplies the shared profile through
the isolated compatibility manager.
