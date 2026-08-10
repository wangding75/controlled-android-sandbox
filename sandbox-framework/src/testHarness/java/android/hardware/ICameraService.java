package android.hardware;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/** Minimal hidden Camera Binder contract used by descriptor and cache self-tests. */
public interface ICameraService extends IInterface {
    int getNumberOfCameras(int type);

    abstract class Stub extends Binder implements ICameraService {
        public static ICameraService asInterface(IBinder binder) {
            IInterface local = binder == null ? null
                    : binder.queryLocalInterface("android.hardware.ICameraService");
            return local instanceof ICameraService ? (ICameraService) local : null;
        }
    }
}
