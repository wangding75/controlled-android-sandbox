# XH camera implementation report

## Evidence

**SOURCE:** XH `VirtualCameraActivity` describes a SurfaceHolder preview and a configured image path, but `loadVirtualImage`, `initCamera`, and the result payload are restore placeholders. It is not proof of the protected camera implementation.

**DECOMPILED:** SX `CameraHook` installs:

- Camera1 `setPreviewCallback`, `setOneShotPreviewCallback`, `setPreviewCallbackWithBuffer`, and `takePicture` hooks;
- Camera2 `ImageReader.acquireLatestImage` and `acquireNextImage` hooks;
- `Image.getPlanes` and `Image.Plane.getBuffer` hooks;
- JPEG and NV21 generation from a bitmap;
- MP4 frame extraction with `MediaMetadataRetriever`, scaling, JPEG encoding and NV21 conversion.

The decompiled implementation reads `CameraConfig.mediaPath` as an absolute path and stores process-global frame buffers. That design is classified as legacy and is not migrated.

## Two separate replacement paths

### Preview replacement

Camera1 preview callbacks receive generated NV21 bytes. The video loop refreshes a JPEG and NV21 frame roughly every 33 ms, with a 640x480 default in the recovered class. Camera2 plane hooks use thread-local pending bytes. The source does not prove a complete Surface/SurfaceTexture buffer lifecycle.

### Final capture replacement

`takePicture` is hooked separately and the picture callback receives generated JPEG bytes. This is the capability required for “DingTalk requests a photo → configured image becomes the result”; it is distinct from preview.

## Controlled Sandbox implementation

- `VirtualCameraSourceSnapshot` contains only relative instance-owned path, MIME, dimensions, orientation, duration and SHA-256.
- `VirtualCameraMediaStore` copies a user-selected URI into the instance Guest files root and never passes a host absolute path to the Guest.
- `VirtualCameraCaptureEngine` verifies the hash, decodes image or video, applies orientation, and emits JPEG or NV21.
- `PeripheralServicesInvocationInterceptor` adapts byte arrays, `ByteBuffer`, and callback methods whose first parameter is `byte[]` for generic Camera1-style preview/capture. Unsupported object-shaped result types fail with an explicit adapter error.
- Camera2 `ICameraDeviceUser` is projected through the generic `media.camera` Binder boundary. The fixture exercised characteristics, stream creation, `SurfaceTexture` preview, JPEG capture, and simultaneous preview/capture targets. JPEG delivery uses a native `ANativeWindow` camera3 BLOB footer; it does not fabricate an `android.media.Image` Java object.
- The generic engine has a bounded video frame sampler and emits JPEG/NV21 payloads. A short MP4 fixture was decoded and delivered to the Camera2 preview/capture path.
- Camera sessions and callback state are bounded and cleaned on close/lease teardown.

## Runtime evidence and remaining boundary

**RUNTIME_OBSERVED:** the generic Camera2 fixture completed image and video runs without a fatal signal. The image source SHA-256 was `258c57db...1682ae8f`; the delivered JPEG was decoded as 1280x720. The video source SHA-256 was `53344945...c9151495`; the delivered JPEG was decoded as 1080x1920. `CS_CAMERA_PREVIEW` and `CS_CAMERA_FRAME` records are in the T53 evidence root.

The real platform `android.media.Image` object is not fabricated by returning a byte array. The successful Camera2 path uses the platform ImageReader consumer and a native camera3 JPEG BLOB transport footer; older Canvas/ImageWriter experiments are retained as crash evidence and removed from production code.

**RUNTIME_OBSERVED:** the legacy `android.hardware.Camera` fixture reached `CameraService::connect` through the native `libcamera_client` path and emitted `CAMERA1_OPENED`, `CAMERA1_PREVIEW_STARTED`, and `CAMERA1_CAPTURE_REQUESTED`. It did not enter the Java `media.camera` Binder proxy, and MuMu revoked host camera access because the projected Guest package/UID was not the host application. No substituted Camera1 capture result is claimed. This is a generic native Camera1 adapter gap, not a DingTalk-specific gap.

**RUNTIME_OBSERVED:** no DingTalk camera entry was reached in this environment, so no DingTalk screenshot/hash/result is claimed here.
