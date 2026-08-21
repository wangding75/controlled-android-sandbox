package com.warden.controlledsandbox;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import org.json.JSONObject;

/**
 * Debug-only isolated process. Not a virtual guest Service. Used only to measure
 * Android isolated UID + Broker capability primitives.
 */
public final class NativeEnforcementIsolatedService extends Service {
    static final String DESCRIPTOR = "com.warden.controlledsandbox.debug.INativeEnforcementChild";
    static final int TX_IDENTITY = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_RUN = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final String TAG = "CS_NATIVE_ENF";

    @Override public IBinder onBind(Intent intent) {
        return new ChildBinder();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private final class ChildBinder extends Binder {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            data.enforceInterface(DESCRIPTOR);
            if (code == TX_IDENTITY) {
                reply.writeNoException();
                reply.writeString(identityJson().toString());
                return true;
            }
            if (code == TX_RUN) {
                IBinder broker = data.readStrongBinder();
                String session = data.readString();
                String fsCap = data.readString();
                String netCap = data.readString();
                String realPath = data.readString();
                String loopbackHost = data.readString();
                int loopbackPort = data.readInt();
                long generation = 1L;
                String guestPackage = getPackageName();
                String fdCap = "";
                boolean production = false;
                if (data.dataAvail() > 0) {
                    generation = data.readLong();
                    guestPackage = data.readString();
                    fdCap = data.readString();
                    production = data.readInt() == 1;
                }
                String result = NativeEnforcementChild.run(NativeEnforcementIsolatedService.this,
                        broker, session, fsCap, netCap, realPath, loopbackHost, loopbackPort,
                        generation, guestPackage, fdCap, production);
                reply.writeNoException();
                reply.writeString(result);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    static JSONObject identityJson() {
        JSONObject out = new JSONObject();
        try {
            int uid = Process.myUid();
            out.put("uid", uid);
            out.put("pid", Process.myPid());
            out.put("appUid", Process.myUid() % 100000);
            out.put("isolated", uid >= 99000 && uid <= 99999);
            String processName = Application.getProcessNameSafe();
            out.put("processName", processName);
            out.put("abi", NativeEnforcementNative.compiledAbi());
            out.put("jniAvailable", NativeEnforcementNative.available());
            if (!NativeEnforcementNative.available()) {
                out.put("jniError", NativeEnforcementNative.loadError());
            }
        } catch (Exception error) {
            Log.e(TAG, "identity json", error);
        }
        return out;
    }

    private static final class Application {
        static String getProcessNameSafe() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return android.app.Application.getProcessName();
            }
            return "unknown";
        }
    }
}
