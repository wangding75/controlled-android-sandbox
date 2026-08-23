package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.widget.FrameLayout;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Package-neutral native buffer/Surface/codec fixture for the C3-T03 RD gate. */
public final class C3T03NativeMediaActivity extends Activity {
    private static final String TAG = "CS_C3_T03_NATIVE_MEDIA";
    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    static {
        System.loadLibrary("controlled_sandbox_fixture");
    }

    private static native String nativeCompiledAbi();
    private static native int nativePageSize();
    private static native String nativeLateDlopen();
    private static native String nativeSurfaceBufferRoundTrip(Surface surface);
    private static native String nativeCodecProbe();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new FrameLayout(this));
        Log.i(TAG, "C3_T03_NATIVE_MEDIA_BEGIN");
        new Thread(this::runProbe, "c3-t03-native-media").start();
    }

    private void runProbe() {
        JSONObject result = new JSONObject();
        try {
            String context = getIntent() == null
                    ? "DIRECT_FIXTURE" : getIntent().getStringExtra("cas.native.context");
            if (context == null || context.trim().isEmpty()) context = "DIRECT_FIXTURE";
            String abi = nativeCompiledAbi();
            int pageSize = nativePageSize();
            result.put("taskId", "C3-T03").put("context", context)
                    .put("compiledAbi", abi).put("nativePageSize", pageSize);

            JSONObject lateLoad = new JSONObject(nativeLateDlopen());
            JSONObject surface = runSurfaceProbe();
            JSONObject codec = new JSONObject(nativeCodecProbe());
            result.put("lateDlopen", lateLoad).put("surface", surface).put("codec", codec);

            boolean imagePass = "PASS".equals(surface.optString("imageStatus"));
            boolean surfacePass = "PASS".equals(surface.optString("nativeStatus"));
            boolean latePass = "PASS".equals(lateLoad.optString("status"));
            boolean codecPass = "PASS".equals(codec.optString("status"));
            String status = surfacePass && imagePass && latePass && codecPass ? "PASS" : "FAIL";
            result.put("status", status).put("cleanup", "PASS");
            writeResult(result);
            Log.i(TAG, "C3_T03_NATIVE_MEDIA_RESULT " + result);
            Log.i(TAG, "C3_T03_NATIVE_MEDIA_RESULT_END status=" + status
                    + " abi=" + abi + " pageSize=" + pageSize);
        } catch (Throwable error) {
            try {
                result.put("taskId", "C3-T03").put("status", "FAIL")
                        .put("cleanup", "ATTEMPTED")
                        .put("error", error.getClass().getSimpleName() + ":" + error.getMessage());
                writeResult(result);
            } catch (Throwable writeError) {
                Log.e(TAG, "C3_T03_NATIVE_MEDIA_RESULT_WRITE_FAILED", writeError);
            }
            Log.e(TAG, "C3_T03_NATIVE_MEDIA_FAIL " + error.getClass().getSimpleName(), error);
        } finally {
            mainHandler.post(this::finish);
        }
    }

    private JSONObject runSurfaceProbe() throws Exception {
        ImageReader reader = null;
        Surface surface = null;
        CountDownLatch imageReady = new CountDownLatch(1);
        AtomicReference<JSONObject> imageResult = new AtomicReference<>();
        try {
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(source -> {
                Image image = null;
                try {
                    image = source.acquireLatestImage();
                    if (image == null) return;
                    Image.Plane[] planes = image.getPlanes();
                    if (planes == null || planes.length == 0) {
                        throw new IllegalStateException("IMAGE_PLANES_EMPTY");
                    }
                    ByteBuffer buffer = planes[0].getBuffer().duplicate();
                    int byteCount = buffer.remaining();
                    if (byteCount <= 0) throw new IllegalStateException("IMAGE_BUFFER_EMPTY");
                    byte[] bytes = new byte[byteCount];
                    buffer.get(bytes);
                    imageResult.set(new JSONObject().put("status", "PASS")
                            .put("width", image.getWidth()).put("height", image.getHeight())
                            .put("format", image.getFormat()).put("bytes", byteCount)
                            .put("rowStride", planes[0].getRowStride())
                            .put("pixelStride", planes[0].getPixelStride())
                            .put("sha256", sha256(bytes)));
                } catch (Throwable error) {
                    imageResult.set(errorJson("FAIL", error));
                } finally {
                    if (image != null) image.close();
                    imageReady.countDown();
                }
            }, mainHandler);
            surface = reader.getSurface();
            JSONObject nativeResult = new JSONObject(nativeSurfaceBufferRoundTrip(surface));
            boolean callback = imageReady.await(5L, TimeUnit.SECONDS);
            JSONObject image = imageResult.get();
            if (image == null) {
                image = new JSONObject().put("status", callback ? "FAIL" : "ENVIRONMENT_NOT_AVAILABLE")
                        .put("error", callback ? "IMAGE_CALLBACK_WITHOUT_RESULT" : "IMAGE_CALLBACK_TIMEOUT");
            }
            return new JSONObject().put("nativeStatus", nativeResult.optString("status"))
                    .put("native", nativeResult).put("imageStatus", image.optString("status"))
                    .put("image", image).put("surfaceFormat", PixelFormat.RGBA_8888);
        } finally {
            if (surface != null) surface.release();
            if (reader != null) reader.close();
        }
    }

    private void writeResult(JSONObject result) throws Exception {
        File output = new File(getFilesDir(), "c3-t03-native-media.json");
        Files.write(output.toPath(), (result.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static JSONObject errorJson(String status, Throwable error) {
        JSONObject result = new JSONObject();
        try {
            result.put("status", status).put("error",
                    error.getClass().getSimpleName() + ":" + error.getMessage());
        } catch (Exception ignored) {
            Log.e(TAG, "C3_T03_NATIVE_MEDIA_ERROR_JSON_FAILED", ignored);
        }
        return result;
    }
}
