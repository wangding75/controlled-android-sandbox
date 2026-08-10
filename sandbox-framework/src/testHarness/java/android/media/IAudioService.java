package android.media;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/** Minimal hidden AIDL shape used to verify the production audio Binder contract. */
public interface IAudioService extends IInterface {
    void routeProbe();

    abstract class Stub extends Binder implements IAudioService {
        public static IAudioService asInterface(IBinder binder) {
            IInterface local = binder == null ? null
                    : binder.queryLocalInterface("android.media.IAudioService");
            return local instanceof IAudioService ? (IAudioService) local : null;
        }
    }
}
