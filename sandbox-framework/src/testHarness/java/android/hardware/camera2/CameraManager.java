package android.hardware.camera2;

import android.hardware.ICameraService;

/** Minimal CameraManagerGlobal cache fixture shared by the Camera hook contract tests. */
public class CameraManager {
    private static final CameraManagerGlobal GLOBAL = new CameraManagerGlobal();

    public CameraManager() { }

    public String[] getCameraIdList() { return new String[] {"0"}; }
    public CameraCharacteristics getCameraCharacteristics(String cameraId) {
        return new CameraCharacteristics();
    }
    public void openCamera(String cameraId, CameraDevice.StateCallback callback,
                           android.os.Handler handler) {
        if (callback != null) callback.onOpened(new CameraDevice());
    }

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
