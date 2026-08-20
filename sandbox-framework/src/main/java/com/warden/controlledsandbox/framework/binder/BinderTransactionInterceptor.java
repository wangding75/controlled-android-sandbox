package com.warden.controlledsandbox.framework.binder;

import android.os.RemoteException;

/**
 * Reusable transaction-level interception contract.  A semantic adapter can inspect or rewrite
 * the live input/output Parcels, then call the continuation exactly once. Exceptions from the
 * delegate are deliberately not converted to fake success values.
 */
@FunctionalInterface
public interface BinderTransactionInterceptor {
    boolean intercept(BinderTransaction transaction, Chain next) throws RemoteException;

    @FunctionalInterface
    interface Chain {
        boolean proceed() throws RemoteException;
    }
}
