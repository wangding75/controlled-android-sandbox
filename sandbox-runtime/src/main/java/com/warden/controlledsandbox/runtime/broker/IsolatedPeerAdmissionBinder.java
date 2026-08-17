package com.warden.controlledsandbox.runtime.broker;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * First-contact admission channel for an isolated worker. The worker presents the
 * session-issued capability; the Binder caller UID becomes the registered isolated peer.
 */
public final class IsolatedPeerAdmissionBinder extends Binder {
    public static final String DESCRIPTOR =
            "com.warden.controlledsandbox.runtime.broker.IsolatedPeerAdmission";
    public static final int TRANSACTION_REGISTER = IBinder.FIRST_CALL_TRANSACTION;

    private final RuntimeIsolatedPeerRegistry registry;
    private final int hostUid;

    IsolatedPeerAdmissionBinder(RuntimeIsolatedPeerRegistry registry, int hostUid) {
        if (registry == null) throw new IllegalArgumentException("registry is required");
        this.registry = registry;
        this.hostUid = hostUid;
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code != TRANSACTION_REGISTER) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        String sessionId = data.readString();
        long generation = data.readLong();
        int slot = data.readInt();
        String token = data.readString();
        try {
            registry.completeRegistration(sessionId, generation, slot, token,
                    Binder.getCallingUid(), hostUid);
            if (reply != null) reply.writeNoException();
            return true;
        } catch (RuntimeException error) {
            if (reply != null) {
                reply.writeException(error instanceof SecurityException
                        ? error : new SecurityException(error.getMessage(), error));
            }
            return true;
        }
    }
}
