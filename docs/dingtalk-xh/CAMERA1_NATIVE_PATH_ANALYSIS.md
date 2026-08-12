# Camera1 native path analysis

Date: 2026-08-12  
Device: MuMu `127.0.0.1:16384`, Android API 32  
Evidence root: `D:\controlled-android-sandbox-evidence\T53-R01-20260812-073729\camera1-final\`

## Conclusion

The Camera1 P1 was a generic interception-boundary defect. The existing Java
`media.camera` Binder proxy covered Camera2, but legacy `android.hardware.Camera`
entered the platform's native camera client directly. The corrected generic
adapter now projects the connection identity at `android::Camera::connect`,
substitutes Camera1 callback/capture data at
`JNICameraContext::copyAndPost`, and removes the context at
`JNICameraContext::release`.

The fixture completed open, preview, NV21 callback, JPEG capture, release and
re-open/release. CameraService no longer revoked camera access. The remaining
MuMu `checkAudioOperation` warning is a shutter-audio attribution issue; it is
not a camera AppOps denial and is recorded as a non-blocking Android/MuMu P2.

## Reconstructed call chain

```text
Guest android.hardware.Camera
  -> framework JNI (native_setup/native_startPreview/native_takePicture)
  -> libandroid_runtime
       android::Camera::connect
       get_native_camera
       JNICameraContext::copyAndPost
       JNICameraContext::release
  -> libcamera_client / ICameraService Binder
  -> CameraService::connect / Camera2ClientBase
  -> camera provider / emulated camera HAL
  -> JNICameraContext callback delivery
```

The pre-fix Java proxy stopped at the `media.camera` service boundary. The
Camera1 call was therefore outside that interception surface. The native
adapter is deliberately located in the generic `libandroid_runtime` PLT/GOT
boundary; it is not conditional on DingTalk or any other package.

## Evidence classification

| Layer | Evidence | Result |
|---|---|---|
| SOURCE | Controlled fixture calls `Camera.open`, `setPreviewCallbackWithBuffer`, `startPreview`, `takePicture`, `release`, then re-opens | Real Camera1 contract is exercised |
| DECOMPILED | API-32 `libandroid_runtime.so` exports `Camera::connect`, `get_native_camera`, `JNICameraContext::copyAndPost`, and `release`; `release` is a PLT relocation | Native hook symbols and lifecycle boundary are real |
| RUNTIME_OBSERVED | CameraService logs show native `connect`, `Camera2ClientBase Opened`, callback and capture delivery | Camera1 reaches the native/Binder service path |
| RUNTIME_OBSERVED | `CS_CAMERA1_NATIVE` reports four patched targets, source hash, `DATA_REPLACED`, and `CONTEXT_RELEASED remaining=0` | Generic adapter is active and tears down contexts |
| INFERENCE | XH's protected Camera1 implementation is not exposed by the recovered XH source/runtime; SX's Java hooks are a legacy reference only | The adapter follows Android's actual boundary, not a copied SX hook |

## Final interception boundary

| Operation | Boundary | Shared capability |
|---|---|---|
| Identity | `android::Camera::connect` replacement | Guest package is projected to the host process identity before the real native connect; the API-32 ABI has `-1,-1,35` in the UID/PID argument slots, so Binder caller identity remains the host UID/PID by design |
| Native handle | `get_native_camera` replacement | Binds the Java `Camera` context to the generic virtual session |
| Preview/capture data | `JNICameraContext::copyAndPost` replacement | `VirtualCameraProfile`, instance-owned `MediaSource`, and `VirtualCameraCaptureEngine` produce NV21/JPEG |
| Lifecycle | `JNICameraContext::release` replacement | Calls the platform release first, then drops the Java weak reference and native context mapping |
| Surface | Platform `SurfaceTexture`/camera stream contract | The fixture uses a real `SurfaceTexture`; callback data is independently substituted. No fake Surface success is used |

The adapter shares the existing `VirtualCameraProfile`, media store,
instance binding, image/video decoder and capture-substitution policy with
Camera2. There is no `Camera1VirtualConfig` and no package-specific camera
branch.

## AppOps differential

Before the fix, CameraService recorded a package/UID mismatch and revoked the
camera operation. After the fix, it sees the host application package and UID
(`com.warden.controlledsandbox.debug`, UID `10199`) and the Camera1 session
opens, previews and captures. The fixture's virtual identity remains the
Guest package/user profile; the native process is the host process, which is
why the projection is required at the native connect boundary.

The post-run `cmd appops get --uid 10199` evidence reports CAMERA and
RECORD_AUDIO as `foreground`. The only residual exception is
`checkAudioOperation` during the platform shutter-audio path. It does not
produce `Access revoked`, does not prevent preview/capture, and was not hidden
by granting AppOps or ignoring an exception. This is classified as
`ANDROID_COMPAT` / MuMu policy P2 and remains outside the Camera1 data plane.

## Camera1 acceptance

The final user0 image run used source SHA-256
`258C57DBA560BE1F67944CDD967A2B2803160824958AF983000D86B01682AE8F`.

- Preview: `1280x720`, format `17` (`NV21`), `1,382,400` bytes, callback
  received and stored.
- Capture: JPEG callback received and stored; decoded dimensions `1280x720`.
- Source/capture decoded pixel equivalence: `True`; mean absolute channel
  differences `[0.4914, 0.0926, 1.3973]`, attributable to JPEG encoding.
- Re-open: second native connect completed and release returned the context
  count to zero.
- Isolation: the adapter key includes virtual user/instance context and the
  source is loaded through the existing instance-owned media store; no host
  absolute path is exposed.
- Fail-closed: disabled or missing/corrupt profiles use the existing explicit
  normal/unavailable policy; no stale source is silently reported as a
  successful capture.

The final user1 run used virtual UID `110002` and portrait source SHA-256
`7A3DF3BCDE0E22A909F25ED4221A41903D4F8196513906EDE1BE3C1EA47213F1`; it
completed the same Camera1 callback/capture/re-open/release contract without
cross-instance source reuse.

The callback bytes are delivered through the real Camera1 callback path; the
fixture does not copy a source file directly into a result directory.

## Video boundary

The common capture engine supports bounded MP4 frame decoding and Camera2
video is runtime-proven. The XH source and runtime evidence available in this
task do not prove that XH specifically virtualized a Camera1 video callback or
Surface path. SX's recovered MP4 loop is classified as `INCOMPLETE LEGACY
IMPLEMENTATION`, not as XH evidence. Therefore this report does not claim an
XH-specific Camera1-video capability; the generic adapter remains ready for a
video source while Camera2 provides the verified video coverage.

## Classification

- `GENERAL_CAMERA`: native Camera1 identity, callback, capture and lifecycle.
- `GENERAL_SYSTEM_VIRTUALIZATION`: Binder caller identity and service reachability.
- `ANDROID_COMPAT`: MuMu shutter-audio attribution warning and vendor Surface/HAL
  behavior.
- `DINGTALK_SPECIFIC`: none in the adapter. DingTalk only configures the shared
  profile through `DingTalkCompatibilityManager`.
