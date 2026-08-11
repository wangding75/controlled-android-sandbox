package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.os.Handler;
import android.util.Log;
import java.util.List;
import org.json.JSONObject;

/** Standard-API fixture used to observe generic F2-F5 projection inside a Guest. */
public final class CapabilityProbeActivity extends Activity {
    private static final String TAG = "CS_CAPABILITY_FIXTURE";
    private LocationManager locationManager;
    private LocationListener listener;
    private SurfaceView previewView;
    private SurfaceHolder previewHolder;
    private SurfaceTexture previewTexture;
    private Surface previewSurface;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        previewView = new SurfaceView(this);
        previewHolder = previewView.getHolder();
        previewHolder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                Log.i(TAG, "CAMERA_PREVIEW_SURFACE_CREATED valid=" + holder.getSurface().isValid());
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.i(TAG, "CAMERA_PREVIEW_SURFACE_CHANGED width=" + width + " height=" + height);
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i(TAG, "CAMERA_PREVIEW_SURFACE_DESTROYED");
            }
        });
        try {
            previewTexture = new SurfaceTexture(11);
            previewTexture.setDefaultBufferSize(1280, 720);
            previewSurface = new Surface(previewTexture);
            Log.i(TAG, "CAMERA_PREVIEW_TEXTURE_READY valid=" + previewSurface.isValid());
        } catch (Throwable error) {
            Log.w(TAG, "CAMERA_PREVIEW_TEXTURE_ERROR", error);
        }
        setContentView(previewView);
        // Give the standard SurfaceView lifecycle a turn before opening Camera2.  This keeps
        // the preview output test independent from the still-image ImageReader output.
        new Handler(Looper.getMainLooper()).postDelayed(this::probe, 1000L);
    }

    private void probe() {
        JSONObject result = new JSONObject();
        try {
            result.put("package", getPackageName());
            result.put("filesDir", String.valueOf(getFilesDir()));
            result.put("androidId", Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ANDROID_ID));
            result.put("brand", Build.BRAND).put("model", Build.MODEL)
                    .put("manufacturer", Build.MANUFACTURER).put("board", Build.BOARD)
                    .put("serial", serial());
            try { telephony(result); }
            catch (Throwable error) { result.put("telephonyFatal", error.getClass().getSimpleName()); }
            try { wifi(result); }
            catch (Throwable error) { result.put("wifiFatal", error.getClass().getSimpleName()); }
            try { location(result); }
            catch (Throwable error) { result.put("locationFatal", error.getClass().getSimpleName()); }
            try { camera(result); }
            catch (Throwable error) { result.put("cameraFatal", error.getClass().getSimpleName()); }
        } catch (Throwable error) {
            try { result.put("probeError", error.getClass().getSimpleName()); }
            catch (Exception ignored) { }
        }
        Log.i(TAG, "PROBE " + result);
    }

    private void telephony(JSONObject result) throws Exception {
        Object namedService = getSystemService("phone");
        result.put("phoneServiceClass", namedService == null ? "null"
                : namedService.getClass().getName());
        TelephonyManager telephony = namedService instanceof TelephonyManager
                ? (TelephonyManager) namedService : getSystemService(TelephonyManager.class);
        if (telephony == null) return;
        result.put("telephonyManagerClass", telephony.getClass().getName());
        PackageManager packages = getPackageManager();
        result.put("featureTelephony", packages.hasSystemFeature("android.hardware.telephony"));
        result.put("featureTelephonyRadio", packages.hasSystemFeature(
                "android.hardware.telephony.radio.access"));
        try { result.put("phoneCount", telephony.getPhoneCount()); }
        catch (Throwable error) { result.put("phoneCountError", error.getClass().getSimpleName()); }
        try { result.put("voiceCapable", telephony.isVoiceCapable()); }
        catch (Throwable error) { result.put("voiceCapableError", error.getClass().getSimpleName()); }
        try { result.put("imei", telephony.getImei()); } catch (Throwable error) { result.put("imeiError", error.getClass().getSimpleName()); }
        try { result.put("meid", telephony.getMeid()); } catch (Throwable error) { result.put("meidError", error.getClass().getSimpleName()); }
        try { result.put("imsi", telephony.getSubscriberId()); } catch (Throwable error) { result.put("imsiError", error.getClass().getSimpleName()); }
        try { result.put("iccid", telephony.getSimSerialNumber()); } catch (Throwable error) { result.put("iccidError", error.getClass().getSimpleName()); }
        try { result.put("operator", telephony.getNetworkOperator()); } catch (Throwable error) { result.put("operatorError", error.getClass().getSimpleName()); }
        try {
            List<?> cells = telephony.getAllCellInfo();
            result.put("cellCount", cells == null ? 0 : cells.size());
            if (cells != null && !cells.isEmpty()) {
                Object cell = cells.get(0);
                result.put("cellType", cell.getClass().getName());
                if (cell instanceof android.telephony.CellInfoLte lte) {
                    android.telephony.CellIdentityLte id = lte.getCellIdentity();
                    result.put("cellMcc", id.getMccString()).put("cellMnc", id.getMncString())
                            .put("cellTac", id.getTac()).put("cellCid", id.getCi())
                            .put("cellPci", id.getPci()).put("cellEarfcn", id.getEarfcn())
                            .put("cellRegistered", lte.isRegistered());
                }
            }
        } catch (Throwable error) { result.put("cellError", error.getClass().getSimpleName()); }
    }

    private void wifi(JSONObject result) throws Exception {
        WifiManager wifi = getSystemService(WifiManager.class);
        if (wifi == null) return;
        WifiInfo info = wifi.getConnectionInfo();
        if (info != null) {
            result.put("ssid", info.getSSID()).put("bssid", info.getBSSID())
                    .put("wifiMac", info.getMacAddress());
        }
        try { result.put("scanCount", wifi.getScanResults() == null ? 0 : wifi.getScanResults().size()); }
        catch (Throwable error) { result.put("scanError", error.getClass().getSimpleName()); }
    }

    private void location(JSONObject result) throws Exception {
        locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) return;
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) putLocation(result, "last", last);
        } catch (Throwable error) { result.put("locationError", error.getClass().getSimpleName()); }
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                try { JSONObject update = new JSONObject(); putLocation(update, "value", location); Log.i(TAG, "LOCATION_CALLBACK " + update); }
                catch (Exception error) { Log.w(TAG, "LOCATION_CALLBACK_ERROR", error); }
            }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        };
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()); }
        catch (Throwable error) { result.put("callbackError", error.getClass().getSimpleName()); }
    }

    private void camera(JSONObject result) throws Exception {
        CameraManager camera = getSystemService(CameraManager.class);
        if (camera == null) return;
        try {
            String[] ids = camera.getCameraIdList();
            result.put("cameraIds", java.util.Arrays.toString(ids));
            if (ids.length > 0) {
                try {
                    result.put("cameraCharacteristicsClass",
                            String.valueOf(camera.getCameraCharacteristics(ids[0]).getClass().getName()));
                    try {
                        camera.openCamera(ids[0], new CameraDevice.StateCallback() {
                            @Override public void onOpened(CameraDevice device) {
                                Log.i(TAG, "CAMERA_OPENED id=" + device.getId());
                                cameraCapture(device, new Handler(Looper.getMainLooper()));
                            }
                            @Override public void onDisconnected(CameraDevice device) {
                                Log.i(TAG, "CAMERA_DISCONNECTED id=" + device.getId());
                            }
                            @Override public void onError(CameraDevice device, int error) {
                                Log.e(TAG, "CAMERA_OPEN_ERROR id=" + device.getId() + " code=" + error);
                            }
                        }, new Handler(Looper.getMainLooper()));
                        result.put("cameraOpenRequested", true);
                    } catch (Throwable error) {
                        result.put("cameraOpenError", error.getClass().getSimpleName());
                        Log.e(TAG, "CAMERA_OPEN_REQUEST_ERROR", error);
                    }
                } catch (Throwable error) {
                    result.put("cameraCharacteristicsError", error.getClass().getSimpleName());
                    Log.e(TAG, "CAMERA_CHARACTERISTICS_ERROR", error);
                }
            }
        }
        catch (Throwable error) {
            result.put("cameraError", error.getClass().getSimpleName());
            Log.e(TAG, "CAMERA_ERROR", error);
        }
    }

    private void cameraCapture(CameraDevice device, Handler handler) {
        final android.media.ImageReader reader = android.media.ImageReader.newInstance(1280, 720,
                android.graphics.ImageFormat.JPEG, 2);
        try {
            reader.setOnImageAvailableListener(source -> {
                android.media.Image image = null;
                try {
                    image = source.acquireLatestImage();
                    if (image == null) {
                        Log.e(TAG, "CAMERA_IMAGE_MISSING");
                        return;
                    }
                    java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    java.nio.file.Files.write(new java.io.File(getFilesDir(),
                            "camera-capture-result.jpg").toPath(), bytes);
                    android.graphics.BitmapFactory.Options options =
                            new android.graphics.BitmapFactory.Options();
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    Log.i(TAG, "CAMERA_CAPTURE_RESULT bytes=" + bytes.length
                            + " sha256=" + toHex(digest.digest(bytes))
                            + " decodedWidth=" + options.outWidth
                            + " decodedHeight=" + options.outHeight
                            + " stored=true");
                } catch (Throwable error) {
                    Log.e(TAG, "CAMERA_CAPTURE_RESULT_ERROR", error);
                } finally {
                    if (image != null) image.close();
                    source.close();
                    try { device.close(); } catch (Throwable error) {
                        Log.w(TAG, "CAMERA_CLOSE_ERROR", error);
                    }
                }
            }, handler);
            java.util.ArrayList<android.view.Surface> targets = new java.util.ArrayList<>();
            targets.add(reader.getSurface());
            if (previewHolder != null && previewHolder.getSurface().isValid()) {
                targets.add(previewHolder.getSurface());
                Log.i(TAG, "CAMERA_PREVIEW_TARGET_ADDED");
            } else if (previewSurface != null && previewSurface.isValid()) {
                targets.add(previewSurface);
                Log.i(TAG, "CAMERA_PREVIEW_TEXTURE_TARGET_ADDED");
            } else {
                Log.w(TAG, "CAMERA_PREVIEW_TARGET_UNAVAILABLE");
            }
            device.createCaptureSession(targets,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            try {
                                CaptureRequest.Builder builder = device.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                                for (android.view.Surface target : targets) builder.addTarget(target);
                                session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() { }, handler);
                                Log.i(TAG, "CAMERA_CAPTURE_REQUESTED targets=" + targets.size());
                            } catch (Throwable error) {
                                Log.e(TAG, "CAMERA_CAPTURE_REQUEST_ERROR", error);
                                session.close();
                                device.close();
                                reader.close();
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "CAMERA_SESSION_CONFIGURE_FAILED");
                            session.close();
                            device.close();
                            reader.close();
                        }
                    }, handler);
                } catch (Throwable error) {
            Log.e(TAG, "CAMERA_SESSION_REQUEST_ERROR", error);
            try { device.close(); } catch (Throwable ignored) { }
            try { reader.close(); } catch (Throwable ignored) { }
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte current : value) output.append(String.format(java.util.Locale.ROOT, "%02x", current));
        return output.toString();
    }

    private static void putLocation(JSONObject output, String prefix, Location value) throws Exception {
        output.put(prefix + "Latitude", value.getLatitude()).put(prefix + "Longitude", value.getLongitude())
                .put(prefix + "Altitude", value.getAltitude()).put(prefix + "Accuracy", value.getAccuracy())
                .put(prefix + "Speed", value.getSpeed()).put(prefix + "Bearing", value.getBearing())
                .put(prefix + "Time", value.getTime()).put(prefix + "ElapsedRealtimeNanos", value.getElapsedRealtimeNanos());
    }

    private static String serial() {
        try { return Build.getSerial(); } catch (Throwable error) { return "ERROR:" + error.getClass().getSimpleName(); }
    }

    @Override protected void onDestroy() {
        if (locationManager != null && listener != null) {
            try { locationManager.removeUpdates(listener); } catch (Throwable ignored) { }
        }
        if (previewSurface != null) {
            try { previewSurface.release(); } catch (Throwable ignored) { }
            previewSurface = null;
        }
        if (previewTexture != null) {
            try { previewTexture.release(); } catch (Throwable ignored) { }
            previewTexture = null;
        }
        super.onDestroy();
    }
}
