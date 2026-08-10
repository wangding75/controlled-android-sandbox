package android.nfc;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/** Minimal hidden NFC Binder contract used by descriptor and synthetic-service self-tests. */
public interface INfcAdapter extends IInterface {
    int getState();
    boolean isEnabled();

    abstract class Stub extends Binder implements INfcAdapter {
        public static INfcAdapter asInterface(IBinder binder) {
            IInterface local = binder == null ? null
                    : binder.queryLocalInterface("android.nfc.INfcAdapter");
            return local instanceof INfcAdapter ? (INfcAdapter) local : null;
        }
    }
}
