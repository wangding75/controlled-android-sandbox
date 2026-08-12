package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/** Camera1-only fixture. It records the native legacy path separately from Camera2. */
@SuppressWarnings("deprecation")
public final class LegacyCameraProbeActivity extends Activity {
    private static final String TAG = "CS_CAMERA1_FIXTURE";
    private Camera camera;
    private SurfaceTexture previewTexture;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean previewCallbackDelivered;
    private boolean captureRequested;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            camera = Camera.open(0);
            Log.i(TAG, "CAMERA1_OPENED");
            previewTexture = new SurfaceTexture(7);
            camera.setPreviewTexture(previewTexture);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size previewSize = parameters.getPreviewSize();
            int previewBytes = previewSize == null ? 0
                    : previewSize.width * previewSize.height * 3 / 2;
            Log.i(TAG, "CAMERA1_PREVIEW_CONFIG width="
                    + (previewSize == null ? 0 : previewSize.width)
                    + " height=" + (previewSize == null ? 0 : previewSize.height)
                    + " format=" + parameters.getPreviewFormat()
                    + " expectedNv21Bytes=" + previewBytes);
            camera.setPreviewCallbackWithBuffer((data, openedCamera) -> {
                if (previewCallbackDelivered) return;
                previewCallbackDelivered = true;
                try {
                    File output = new File(getFilesDir(), "camera1-preview-callback.nv21");
                    Files.write(output.toPath(), data == null ? new byte[0] : data);
                    Log.i(TAG, "CAMERA1_PREVIEW_CALLBACK bytes="
                            + (data == null ? 0 : data.length)
                            + " sha256=" + (data == null ? "" : sha256(data))
                            + " format=NV21 stored=true");
                } catch (Throwable error) {
                    Log.e(TAG, "CAMERA1_PREVIEW_CALLBACK_ERROR", error);
                }
                requestCapture();
            });
            if (previewBytes > 0) camera.addCallbackBuffer(new byte[previewBytes]);
            camera.startPreview();
            Log.i(TAG, "CAMERA1_PREVIEW_STARTED");
            mainHandler.postDelayed(() -> {
                if (!previewCallbackDelivered) {
                    Log.e(TAG, "CAMERA1_PREVIEW_CALLBACK_TIMEOUT");
                    requestCapture();
                }
            }, 2500L);
        } catch (Throwable error) {
            Log.e(TAG, "CAMERA1_FAILURE", error);
            releaseCamera();
        }
    }

    private void requestCapture() {
        if (captureRequested || camera == null) return;
        captureRequested = true;
        try {
            camera.takePicture(null, null, (data, openedCamera) -> {
                try {
                    if (data == null || data.length == 0) throw new IOException("empty JPEG");
                    File output = new File(getFilesDir(), "camera1-capture-result.jpg");
                    Files.write(output.toPath(), data);
                    Log.i(TAG, "CAMERA1_CAPTURE_RESULT bytes=" + data.length
                            + " sha256=" + sha256(data) + " stored=true");
                } catch (Throwable error) {
                    Log.e(TAG, "CAMERA1_CAPTURE_RESULT_ERROR", error);
                } finally {
                    try { openedCamera.release(); } catch (Throwable ignored) { }
                    camera = null;
                    reopenCamera();
                }
            });
            Log.i(TAG, "CAMERA1_CAPTURE_REQUESTED");
        } catch (Throwable error) {
            Log.e(TAG, "CAMERA1_CAPTURE_REQUEST_ERROR", error);
            releaseCamera();
        }
    }

    private void reopenCamera() {
        try {
            Camera reopened = Camera.open(0);
            Log.i(TAG, "CAMERA1_REOPENED");
            reopened.release();
            Log.i(TAG, "CAMERA1_REOPEN_RELEASED");
        } catch (Throwable error) {
            Log.e(TAG, "CAMERA1_REOPEN_ERROR", error);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte item : digest) output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return output.toString();
    }

    @Override protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        releaseCamera();
        if (previewTexture != null) {
            try { previewTexture.release(); } catch (Throwable ignored) { }
            previewTexture = null;
        }
        super.onDestroy();
    }

    private void releaseCamera() {
        if (camera != null) {
            try { camera.release(); } catch (Throwable ignored) { }
            camera = null;
        }
    }
}
