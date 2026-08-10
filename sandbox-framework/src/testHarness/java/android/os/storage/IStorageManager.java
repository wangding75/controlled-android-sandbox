package android.os.storage;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IStorageManager extends IInterface {
    String probe();

    abstract class Stub extends Binder implements IStorageManager {
        public static IStorageManager asInterface(IBinder binder) {
            IInterface local = binder == null ? null
                    : binder.queryLocalInterface("android.os.storage.IStorageManager");
            return local instanceof IStorageManager ? (IStorageManager) local : null;
        }
    }
}
