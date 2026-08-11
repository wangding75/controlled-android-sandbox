package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            camera = Camera.open(0);
            Log.i(TAG, "CAMERA1_OPENED");
            previewTexture = new SurfaceTexture(7);
            camera.setPreviewTexture(previewTexture);
            camera.startPreview();
            Log.i(TAG, "CAMERA1_PREVIEW_STARTED");
            camera.takePicture(null, null, (data, openedCamera) -> {
                try {
                    File output = new File(getFilesDir(), "camera1-capture-result.jpg");
                    Files.write(output.toPath(), data);
                    Log.i(TAG, "CAMERA1_CAPTURE_RESULT bytes=" + data.length
                            + " sha256=" + sha256(data) + " stored=true");
                } catch (Throwable error) {
                    Log.e(TAG, "CAMERA1_CAPTURE_RESULT_ERROR", error);
                } finally {
                    try { openedCamera.release(); } catch (Throwable ignored) { }
                    camera = null;
                }
            });
            Log.i(TAG, "CAMERA1_CAPTURE_REQUESTED");
        } catch (Throwable error) {
            Log.e(TAG, "CAMERA1_FAILURE", error);
            releaseCamera();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte item : digest) output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return output.toString();
    }

    @Override protected void onDestroy() {
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
