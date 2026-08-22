package com.warden.controlledsandbox.fixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.FrameLayout;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Package-neutral Camera1/Camera2 campaign fixture for the C2-T04 RD gate. */
@SuppressWarnings("deprecation")
public final class CameraCampaignActivity extends Activity {
    private static final String TAG = "CS_C2_T04_CAMERA";
    private static final String INTENT_EXTRAS = "intentExtras";
    private static final String MODE_KEY = "c2t04Mode";
    private static final int DEFAULT_LOOPS = 100;
    private static final int DEFAULT_PRESSURE_SECONDS = 1800;
    private static final long CAMERA_TIMEOUT_SECONDS = 12L;
    private static final int FORMAT_NV21 = 17;
    private static final int FORMAT_YUV_420_888 = 35;
    private static final int FORMAT_JPEG = 256;

    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());
    private Handler cameraHandler;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new FrameLayout(this));
        cameraHandler = new Handler(android.os.Looper.getMainLooper());
        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        if (extras != null && extras.getBundle(INTENT_EXTRAS) != null) {
            extras = extras.getBundle(INTENT_EXTRAS);
        }
        final Bundle launchExtras = extras == null ? new Bundle() : new Bundle(extras);
        final String mode = launchExtras.getString(MODE_KEY, "smoke").trim().toLowerCase(Locale.ROOT);
        final int loops = Math.max(1, Math.min(1000, launchExtras.getInt("c2t04Loops", DEFAULT_LOOPS)));
        final int pressureSeconds = Math.max(1, Math.min(86_400,
                launchExtras.getInt("c2t04PressureSeconds", DEFAULT_PRESSURE_SECONDS)));
        Log.i(TAG, "C2_T04_CAMERA_BEGIN mode=" + mode + " loops=" + loops
                + " pressureSeconds=" + pressureSeconds);
        new Thread(() -> runCampaign(mode, loops, pressureSeconds), "c2-t04-camera-campaign").start();
    }

    private void runCampaign(String mode, int loops, int pressureSeconds) {
        try {
            switch (mode) {
                case "smoke" -> {
                    runCamera1Smoke();
                    runCamera2Smoke();
                    Log.i(TAG, "C2_T04_CAMERA_SMOKE_PASS");
                }
                case "loops" -> {
                    runCamera1Loops(loops);
                    runCamera2Loops(loops);
                    Log.i(TAG, "C2_T04_CAMERA_LOOPS_PASS count=" + loops);
                }
                case "preview", "pressure" -> {
                    runCamera2Preview(pressureSeconds);
                    Log.i(TAG, "C2_T04_CAMERA_PREVIEW_PASS seconds=" + pressureSeconds);
                }
                case "recovery" -> {
                    runCamera2Smoke();
                    Log.i(TAG, "C2_T04_CAMERA_RECOVERY_READY");
                }
                default -> throw new IllegalArgumentException("unknown camera campaign mode: " + mode);
            }
            mainHandler.post(this::finish);
        } catch (Throwable error) {
            Log.e(TAG, "C2_T04_CAMERA_FAIL phase=" + mode
                    + " error=" + error.getClass().getSimpleName()
                    + " message=" + String.valueOf(error.getMessage()), error);
            mainHandler.post(this::finish);
        }
    }

    private void runCamera1Smoke() throws Exception {
        Camera camera = null;
        SurfaceTexture texture = null;
        CountDownLatch preview = new CountDownLatch(1);
        CountDownLatch capture = new CountDownLatch(1);
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        try {
            requireVirtualCameraPermission();
            camera = Camera.open(0);
            Log.i(TAG, "C2_T04_CAMERA1_OPEN");
            texture = new SurfaceTexture(17);
            camera.setPreviewTexture(texture);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = parameters.getPreviewSize();
            int width = size == null ? 0 : size.width;
            int height = size == null ? 0 : size.height;
            int expectedBytes = width > 0 && height > 0 ? width * height * 3 / 2 : 0;
            int previewFormat = parameters.getPreviewFormat();
            JSONObject config = new JSONObject().put("width", width).put("height", height)
                    .put("format", previewFormat).put("formatName", formatName(previewFormat))
                    .put("expectedBytes", expectedBytes);
            Log.i(TAG, "C2_T04_CAMERA1_CONFIG " + config);
            final Camera openedCamera = camera;
            camera.setPreviewCallbackWithBuffer((data, callbackCamera) -> {
                if (preview.getCount() == 0) return;
                try {
                    byte[] bytes = data == null ? new byte[0] : data.clone();
                    File output = new File(getFilesDir(), "c2-t04-camera1-preview.nv21");
                    Files.write(output.toPath(), bytes);
                    JSONObject record = new JSONObject().put("width", width)
                            .put("height", height).put("format", previewFormat)
                            .put("formatName", formatName(previewFormat)).put("bytes", bytes.length)
                            .put("sha256", sha256(bytes)).put("timestampNs", System.nanoTime());
                    Log.i(TAG, "C2_T04_CAMERA1_PREVIEW " + record);
                } catch (Throwable error) {
                    callbackError.compareAndSet(null, error);
                } finally {
                    preview.countDown();
                    callbackCamera.setPreviewCallbackWithBuffer(null);
                }
            });
            if (expectedBytes > 0) openedCamera.addCallbackBuffer(new byte[expectedBytes]);
            openedCamera.startPreview();
            if (!await(preview, CAMERA_TIMEOUT_SECONDS)) {
                throw new IllegalStateException("CAMERA1_PREVIEW_TIMEOUT");
            }
            if (callbackError.get() != null) throw new IllegalStateException(
                    "CAMERA1_PREVIEW_CALLBACK_FAILED", callbackError.get());
            openedCamera.takePicture(null, null, (data, callbackCamera) -> {
                try {
                    byte[] bytes = data == null ? new byte[0] : data;
                    if (bytes.length == 0) throw new IllegalStateException("empty JPEG");
                    Files.write(new File(getFilesDir(), "c2-t04-camera1-capture.jpg").toPath(), bytes);
                    JSONObject record = new JSONObject().put("width", width).put("height", height)
                            .put("format", "JPEG").put("bytes", bytes.length)
                            .put("sha256", sha256(bytes)).put("timestampNs", System.nanoTime());
                    Log.i(TAG, "C2_T04_CAMERA1_CAPTURE " + record);
                } catch (Throwable error) {
                    callbackError.compareAndSet(null, error);
                } finally {
                    capture.countDown();
                }
            });
            if (!await(capture, CAMERA_TIMEOUT_SECONDS)) {
                throw new IllegalStateException("CAMERA1_CAPTURE_TIMEOUT");
            }
            if (callbackError.get() != null) throw new IllegalStateException(
                    "CAMERA1_CAPTURE_CALLBACK_FAILED", callbackError.get());
        } finally {
            if (camera != null) {
                try { camera.setPreviewCallbackWithBuffer(null); } catch (Throwable ignored) { }
                try { camera.release(); } catch (Throwable ignored) { }
            }
            if (texture != null) {
                try { texture.release(); } catch (Throwable ignored) { }
            }
        }
        Camera reopened = null;
        try {
            requireVirtualCameraPermission();
            reopened = Camera.open(0);
            Log.i(TAG, "C2_T04_CAMERA1_REOPEN");
        } finally {
            if (reopened != null) {
                try { reopened.release(); } catch (Throwable ignored) { }
            }
            Log.i(TAG, "C2_T04_CAMERA1_REOPEN_RELEASED");
        }
    }

    private void runCamera1Loops(int loops) throws Exception {
        int opened = 0;
        for (int index = 0; index < loops; index++) {
            Camera camera = null;
            try {
                requireVirtualCameraPermission();
                camera = Camera.open(0);
                opened++;
            } finally {
                if (camera != null) camera.release();
            }
            if ((index + 1) % 10 == 0 || index + 1 == loops) {
                Log.i(TAG, "C2_T04_CAMERA1_LOOP progress=" + (index + 1) + "/" + loops);
            }
        }
        if (opened != loops) throw new IllegalStateException("CAMERA1_LOOP_COUNT_MISMATCH");
    }

    private void requireVirtualCameraPermission() {
        if (checkSelfPermission("android.permission.CAMERA")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("GUEST_CAMERA_PERMISSION_DENIED");
        }
    }

    @SuppressLint("MissingPermission")
    private void runCamera2Smoke() throws Exception {
        logCamera2Discovery();
        Camera2Session session = openCamera2Session();
        try {
            submitStill(session);
            if (!await(session.firstImage, CAMERA_TIMEOUT_SECONDS)) {
                throw new IllegalStateException("CAMERA2_IMAGE_TIMEOUT");
            }
            if (session.callbackError.get() != null) throw new IllegalStateException(
                    "CAMERA2_IMAGE_CALLBACK_FAILED", session.callbackError.get());
            if (session.frames.get() < 1) throw new IllegalStateException("CAMERA2_FRAME_COUNT_ZERO");
        } finally {
            session.close();
        }
    }

    @SuppressLint("MissingPermission")
    private void runCamera2Loops(int loops) throws Exception {
        logCamera2Discovery();
        int opened = 0;
        for (int index = 0; index < loops; index++) {
            CameraDevice device = openCamera2Device();
            opened++;
            device.close();
            if ((index + 1) % 10 == 0 || index + 1 == loops) {
                Log.i(TAG, "C2_T04_CAMERA2_LOOP progress=" + (index + 1) + "/" + loops);
            }
        }
        if (opened != loops) throw new IllegalStateException("CAMERA2_LOOP_COUNT_MISMATCH");
    }

    @SuppressLint("MissingPermission")
    private void runCamera2Preview(int pressureSeconds) throws Exception {
        logCamera2Discovery();
        Camera2Session session = openCamera2Session();
        try {
            submitRepeating(session);
            if (!await(session.firstImage, CAMERA_TIMEOUT_SECONDS)) {
                throw new IllegalStateException("CAMERA2_PREVIEW_FIRST_FRAME_TIMEOUT");
            }
            long deadline = System.currentTimeMillis() + pressureSeconds * 1000L;
            long nextProgress = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                if (destroyed) throw new IllegalStateException("CAMERA2_PREVIEW_ACTIVITY_DESTROYED");
                long remaining = Math.max(1L, deadline - System.currentTimeMillis());
                Thread.sleep(Math.min(1000L, remaining));
                if (System.currentTimeMillis() >= nextProgress) {
                    Log.i(TAG, "C2_T04_CAMERA2_PREVIEW_PROGRESS seconds="
                            + Math.max(0L, (deadline - System.currentTimeMillis()) / 1000L)
                            + " frames=" + session.frames.get()
                            + " results=" + session.results.get());
                    nextProgress = System.currentTimeMillis() + 30_000L;
                }
            }
            if (session.frames.get() < 1) throw new IllegalStateException("CAMERA2_PREVIEW_NO_FRAMES");
        } finally {
            session.close();
        }
    }

    @SuppressLint("MissingPermission")
    private void logCamera2Discovery() throws Exception {
        CameraManager manager = getSystemService(CameraManager.class);
        if (manager == null) throw new IllegalStateException("CAMERA2_MANAGER_MISSING");
        String[] ids = manager.getCameraIdList();
        if (ids == null || ids.length == 0) throw new IllegalStateException("CAMERA2_ID_LIST_EMPTY");
        String id = ids[0];
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
        int[] formats = new int[0];
        try {
            Field keyField = CameraCharacteristics.class.getField("SCALER_STREAM_CONFIGURATION_MAP");
            Object key = keyField.get(null);
            Method get = null;
            for (Method candidate : characteristics.getClass().getMethods()) {
                if (candidate.getName().equals("get") && candidate.getParameterCount() == 1) {
                    get = candidate;
                    break;
                }
            }
            Object map = get == null ? null : get.invoke(characteristics, key);
            if (map != null) {
                Method outputFormats = map.getClass().getMethod("getOutputFormats");
                Object value = outputFormats.invoke(map);
                if (value instanceof int[] values) formats = values;
            }
        } catch (Throwable error) {
            Log.w(TAG, "C2_T04_CAMERA2_FORMAT_DISCOVERY_ERROR", error);
        }
        JSONObject record = new JSONObject().put("id", id).put("ids", Arrays.toString(ids))
                .put("formats", Arrays.toString(formats))
                .put("jpegAdvertised", contains(formats, FORMAT_JPEG))
                .put("yuv420Advertised", contains(formats, FORMAT_YUV_420_888));
        Log.i(TAG, "C2_T04_CAMERA2_DISCOVERY " + record);
    }

    @SuppressLint("MissingPermission")
    private CameraDevice openCamera2Device() throws Exception {
        CameraManager manager = getSystemService(CameraManager.class);
        if (manager == null) throw new IllegalStateException("CAMERA2_MANAGER_MISSING");
        String[] ids = manager.getCameraIdList();
        if (ids == null || ids.length == 0) throw new IllegalStateException("CAMERA2_ID_LIST_EMPTY");
        CountDownLatch opened = new CountDownLatch(1);
        AtomicReference<CameraDevice> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        manager.openCamera(ids[0], new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice camera) {
                result.set(camera);
                opened.countDown();
            }
            @Override public void onDisconnected(CameraDevice camera) {
                if (result.get() == null) error.compareAndSet(null,
                        new IllegalStateException("CAMERA2_DISCONNECTED_BEFORE_OPEN"));
                opened.countDown();
            }
            @Override public void onError(CameraDevice camera, int code) {
                error.compareAndSet(null, new IllegalStateException("CAMERA2_OPEN_ERROR_" + code));
                opened.countDown();
            }
        }, cameraHandler);
        if (!await(opened, CAMERA_TIMEOUT_SECONDS)) throw new IllegalStateException("CAMERA2_OPEN_TIMEOUT");
        if (error.get() != null) throw new IllegalStateException("CAMERA2_OPEN_FAILED", error.get());
        CameraDevice device = result.get();
        if (device == null) throw new IllegalStateException("CAMERA2_OPEN_RESULT_MISSING");
        Log.i(TAG, "C2_T04_CAMERA2_OPEN id=" + device.getId());
        return device;
    }

    @SuppressLint("MissingPermission")
    private Camera2Session openCamera2Session() throws Exception {
        CameraDevice device = openCamera2Device();
        ImageReader reader = ImageReader.newInstance(320, 240, ImageFormat.JPEG, 3);
        Camera2Session session = new Camera2Session(device, reader);
        reader.setOnImageAvailableListener(source -> {
            Image image = null;
            try {
                image = source.acquireLatestImage();
                if (image == null) return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer().duplicate();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                int number = session.frames.incrementAndGet();
                if (number == 1) {
                    Files.write(new File(getFilesDir(), "c2-t04-camera2-capture.jpg").toPath(), bytes);
                    JSONObject record = new JSONObject().put("width", 320)
                            .put("height", 240).put("format", FORMAT_JPEG)
                            .put("formatName", "JPEG").put("bytes", bytes.length)
                            .put("sha256", sha256(bytes)).put("timestampNs", System.nanoTime());
                    Log.i(TAG, "C2_T04_CAMERA2_IMAGE " + record);
                }
                session.firstImage.countDown();
            } catch (Throwable error) {
                session.callbackError.compareAndSet(null, error);
                session.firstImage.countDown();
            } finally {
                if (image != null) image.close();
            }
        }, cameraHandler);
        CountDownLatch configured = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession value) {
                        session.captureSession = value;
                        configured.countDown();
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession value) {
                        error.compareAndSet(null,
                                new IllegalStateException("CAMERA2_SESSION_CONFIGURE_FAILED"));
                        configured.countDown();
                    }
                }, cameraHandler);
        if (!await(configured, CAMERA_TIMEOUT_SECONDS)) {
            session.close();
            throw new IllegalStateException("CAMERA2_SESSION_CONFIGURE_TIMEOUT");
        }
        if (error.get() != null) {
            session.close();
            throw new IllegalStateException("CAMERA2_SESSION_CONFIGURE_ERROR", error.get());
        }
        if (session.captureSession == null) {
            session.close();
            throw new IllegalStateException("CAMERA2_SESSION_MISSING");
        }
        Log.i(TAG, "C2_T04_CAMERA2_SESSION_READY format=JPEG width=320 height=240");
        return session;
    }

    private void submitRepeating(Camera2Session session) throws Exception {
        CaptureRequest.Builder builder = session.device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE);
        builder.addTarget(session.reader.getSurface());
        setRepeatingRequest(session.captureSession, builder.build(), session.callback);
        Log.i(TAG, "C2_T04_CAMERA2_RESULT_REQUESTED repeating=true");
    }

    private void submitStill(Camera2Session session) throws Exception {
        CaptureRequest.Builder builder = session.device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE);
        builder.addTarget(session.reader.getSurface());
        session.captureSession.capture(builder.build(), session.callback, cameraHandler);
        Log.i(TAG, "C2_T04_CAMERA2_RESULT_REQUESTED repeating=false");
    }

    private static void setRepeatingRequest(CameraCaptureSession session, CaptureRequest request,
                                            CameraCaptureSession.CaptureCallback callback)
            throws Exception {
        for (Method method : session.getClass().getMethods()) {
            if (!method.getName().equals("setRepeatingRequest")
                    || method.getParameterCount() != 3) continue;
            method.invoke(session, request, callback,
                    new Handler(android.os.Looper.getMainLooper()));
            return;
        }
        session.capture(request, callback, new Handler(android.os.Looper.getMainLooper()));
    }

    private static void invokeNoArg(Object target, String name) {
        if (target == null) return;
        try {
            Method method = target.getClass().getMethod(name);
            method.invoke(target);
        } catch (Throwable ignored) { }
    }

    private static boolean await(CountDownLatch latch, long seconds) throws InterruptedException {
        return latch.await(seconds, TimeUnit.SECONDS);
    }

    private static boolean contains(int[] values, int expected) {
        for (int value : values) if (value == expected) return true;
        return false;
    }

    private static String formatName(int format) {
        if (format == FORMAT_JPEG) return "JPEG";
        if (format == FORMAT_NV21) return "NV21";
        if (format == FORMAT_YUV_420_888) return "YUV_420_888";
        return String.valueOf(format);
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte item : digest) output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return output.toString();
    }

    private static final class Camera2Session implements AutoCloseable {
        final CameraDevice device;
        final ImageReader reader;
        final CountDownLatch firstImage = new CountDownLatch(1);
        final AtomicInteger frames = new AtomicInteger();
        final AtomicInteger results = new AtomicInteger();
        final AtomicReference<Throwable> callbackError = new AtomicReference<>();
        final CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() { };
        CameraCaptureSession captureSession;

        Camera2Session(CameraDevice device, ImageReader reader) {
            this.device = device;
            this.reader = reader;
        }

        @Override public void close() {
            if (captureSession != null) {
                invokeNoArg(captureSession, "stopRepeating");
                invokeNoArg(captureSession, "abortCaptures");
                try { captureSession.close(); } catch (Throwable ignored) { }
            }
            try { device.close(); } catch (Throwable ignored) { }
            try { reader.close(); } catch (Throwable ignored) { }
            Log.i(TAG, "C2_T04_CAMERA2_SESSION_CLOSED frames=" + frames.get()
                    + " results=" + results.get());
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
