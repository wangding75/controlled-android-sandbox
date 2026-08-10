package com.android.internal.telephony;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/** Minimal hidden AIDL shape used to exercise the production SMS service contract. */
public interface ISms extends IInterface {
    String probe();

    abstract class Stub extends Binder implements ISms {
        public static ISms asInterface(IBinder binder) {
            IInterface local = binder == null ? null
                    : binder.queryLocalInterface("com.android.internal.telephony.ISms");
            return local instanceof ISms ? (ISms) local : null;
        }
    }
}
