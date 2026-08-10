package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;

import java.util.List;

/** Stable Camera2 Binder, manager-cache and PackageManager feature contract for API32/API35. */
public final class CameraServiceContract {
    public static final String SERVICE_NAME = "media.camera";
    public static final List<String> SERVICE_NAMES = List.of(SERVICE_NAME);
    public static final String DESCRIPTOR = "android.hardware.ICameraService";
    public static final String LOGICAL_SERVICE = "camera";
    public static final String CAMERA_MANAGER_GLOBAL_CLASS =
            "android.hardware.camera2.CameraManager$CameraManagerGlobal";
    public static final String CAMERA_MANAGER_GLOBAL_GETTER = "get";
    public static final String CAMERA_MANAGER_SERVICE_FIELD = "mCameraService";
    public static final String FEATURE_CAMERA = "android.hardware.camera";
    public static final String FEATURE_CAMERA_FRONT = "android.hardware.camera.front";
    public static final String FEATURE_CAMERA_ANY = "android.hardware.camera.any";

    private CameraServiceContract() { }

    public static boolean isCameraFeature(String featureName) {
        return FEATURE_CAMERA.equals(featureName)
                || FEATURE_CAMERA_FRONT.equals(featureName)
                || FEATURE_CAMERA_ANY.equals(featureName);
    }

    /** Static/blocked profiles own guest feature truth; HOST delegates to the real platform. */
    public static boolean guestFeatureEnabled(
            VirtualCameraProfileSnapshot profile, String featureName) {
        if (profile == null) throw new IllegalArgumentException("Camera profile is required");
        if (!isCameraFeature(featureName)) {
            throw new IllegalArgumentException("Unsupported camera feature: " + featureName);
        }
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return true;
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())
                || !profile.cameraAvailable() || profile.cameraIds().isEmpty()) return false;
        if (FEATURE_CAMERA_FRONT.equals(featureName)) {
            return !profile.frontCameraIds().isEmpty();
        }
        return true;
    }
}
