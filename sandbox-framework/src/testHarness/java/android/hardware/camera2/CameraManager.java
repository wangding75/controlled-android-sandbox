package android.hardware.camera2;

import android.hardware.ICameraService;

/** Minimal CameraManagerGlobal cache fixture shared by the Camera hook contract tests. */
public final class CameraManager {
    private static final CameraManagerGlobal GLOBAL = new CameraManagerGlobal();

    private CameraManager() { }

    public static ICameraService cachedServiceForTest() {
        return GLOBAL.mCameraService;
    }

    public static void setCachedServiceForTest(ICameraService service) {
        GLOBAL.mCameraService = service;
    }

    private static final class CameraManagerGlobal {
        private ICameraService mCameraService;

        private static CameraManagerGlobal get() {
            return GLOBAL;
        }
    }
}
