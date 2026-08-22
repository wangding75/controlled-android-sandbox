# C2-T04 Camera1/Camera2 RD API32 Design

## Scope and evidence boundary

This document defines the package-neutral camera campaign for `C2-T04`. It closes the
method families `C2-T01-F3-01` through `C2-T01-F3-04` only on the dynamically resolved MuMu
`RD测试` API32 device. A passing run is `RD_BASELINE`/`RD_API32_L3`; it does not imply an
Android Matrix, OEM/HAL, ARM, 16 KB, hostile-native, or VA PRO equivalent result.

The campaign uses the existing generic `VirtualCameraProfileSnapshot` and source store. The
fixture does not branch on SX, XH, DingTalk, or any product package name. The source file is a
deterministic test image copied into the Guest-owned camera source root; the framework logs the
source SHA-256 at frame delivery, and the fixture records the delivered bytes separately.

## Discovery and classification

The existing implementation already had:

* descriptor-validated `media.camera` and `CameraManagerGlobal.mCameraService` projection;
* a typed peripheral camera handler with camera IDs, characteristics, open quotas, torch policy,
  Camera2 request/surface adaptation, and Guest-owned source verification;
* a native Camera1 adapter which materializes NV21 preview and JPEG capture frames from the
  Guest-owned source;
* a legacy Camera1 activity and contract self-tests.

Before this task there was no package-neutral Camera2/ImageReader fixture, no correlation between
source delivery and frame/result metadata, no 100-cycle reopen/release campaign, and no camera
death/cleanup receipt. These findings are classified as `KI-R03-036` (`TEST_EVIDENCE_GAP`).
The continuation false positive for negative serial guards is `KI-R03-037` and is fixed in the
continuation checker; it does not authorize a hard-coded device endpoint.

## Runtime contract

| Surface | Request | Required observation | Cleanup/death gate |
|---|---|---|---|
| Camera1 preview | `Camera.open`, parameters, `setPreviewCallbackWithBuffer`, `startPreview` | NV21 bytes, width/height, format, source SHA correlation, callback sequence | `setPreviewCallback(null)`, `stopPreview`, `release`, callback stops |
| Camera1 capture | `takePicture` | non-empty JPEG, dimensions, SHA-256, capture callback | release/reopen succeeds; no stale callback |
| Camera2 discovery | `getCameraIdList`, characteristics | stable ID, front/back and sensor metadata | denied profile exposes no IDs |
| Camera2 session | `openCamera`, `createCaptureSession`, repeating request | ImageReader JPEG frame, Surface target, dimensions/format/timestamp, source SHA correlation | session/device/ImageReader close; callback stops |
| Camera2 lifecycle | 100 open/close cycles and process death | generation/PID replacement and no fatal/ANR | post-stop process empty and camera lease count returns to zero |

Camera1 supplies the NV21 contract and Camera2 supplies the ImageReader/JPEG Surface contract.
The Camera2 discovery record also reports whether `YUV_420_888` is advertised; the current
delivery path intentionally keeps YUV coverage on Camera1 NV21 and records that boundary rather
than fabricating a YUV ImageReader frame.
The campaign records `CS_CAMERA_FRAME` source delivery lines and fixture JSON markers in one
raw logcat stream, so a non-empty callback alone cannot pass without a matching source/frame
record. Empty preview or a no-crash result is insufficient.

## Campaign phases

1. Build/install the locked Host, fixture64, fixture32, and Companion32 artifacts and record
   their SHA-256 values.
2. Resolve `RD测试` by instance name, reset Host/Guest state, import the package, grant CAMERA,
   enable camera source substitution, and verify the configured source hash.
3. Run one Camera1 and one Camera2 smoke session, validating source, bytes, dimensions, format,
   timestamps, result/image callbacks, and explicit release.
4. Run 100 Camera1 and 100 Camera2 open/close cycles. Each cycle must report a matching open and
   close, and the aggregate must contain no fatal, ANR, stale-session, or service-rejected marker.
5. Run the Camera2 repeating preview for 1800 seconds (30 分钟). The runner periodically records progress;
   the fixture emits bounded frames and closes resources before reporting PASS.
6. Revoke the virtual CAMERA permission and AppOps, require the Guest to fail before any camera
   open, restore permission/AppOps, and verify a post-revocation smoke session succeeds.
7. Force-stop and relaunch the Guest, then verify a new generation/PID can open the camera and
   that the old session/callback cannot deliver after death. Clear the Guest instance and verify
   source/session residue is absent.

## Evidence schema

`verification/catch-up/C2-T04/c2-t04-rd-summary.json` is the authoritative receipt. It includes
the branch/commit/tree, dynamic device snapshot, APK hashes, source hash, per-phase counts,
frame metadata, cleanup/death observations, raw log path, Known Issue mapping, and explicit
`RD_BASELINE`/`va_pro_equivalent=NOT_PROVEN` scope. The raw log remains under
`artifacts/capability-audit/catch-up-c2-t04/`.

## Failure policy

Runner/harness failures are classified separately from runtime failures. The resource lifecycle
is checked by explicit close markers and post-stop process convergence. A malformed marker,
truncated log, or stale output is repaired and rerun when the defect is local and deterministic.
A real runtime failure is not renamed PASS. If the 100-cycle, 30-minute, or cleanup gate remains
unproven after safe fixes, the task receipt is `BLOCKED` with the exact recovery condition.
