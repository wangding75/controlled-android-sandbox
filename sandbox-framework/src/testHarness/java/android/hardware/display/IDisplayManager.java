package android.hardware.display;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/** Test fixture for the stable IDisplayManager Binder contract. */
public interface IDisplayManager extends IInterface {
    int[] getDisplayIds();
    Object getDisplayInfo(int displayId);

    abstract class Stub extends Binder implements IDisplayManager {
        public static IDisplayManager asInterface(IBinder binder) {
            return binder == null ? null
                    : (IDisplayManager) binder.queryLocalInterface(
                            "android.hardware.display.IDisplayManager");
        }
    }
}
