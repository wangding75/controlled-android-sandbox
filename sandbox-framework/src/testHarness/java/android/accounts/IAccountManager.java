package android.accounts;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IAccountManager extends IInterface {
    boolean getAccountVisibility();

    abstract class Stub extends Binder implements IAccountManager {
        public static IAccountManager asInterface(IBinder binder) {
            IInterface local = binder == null ? null
                    : binder.queryLocalInterface("android.accounts.IAccountManager");
            return local instanceof IAccountManager ? (IAccountManager) local : null;
        }
    }
}
