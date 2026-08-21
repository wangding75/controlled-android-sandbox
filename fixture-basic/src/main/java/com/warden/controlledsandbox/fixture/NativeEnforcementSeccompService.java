package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import org.json.JSONObject;

/** Isolated fixture process. Seccomp probe only. Not a guest runtime Service. */
public final class NativeEnforcementSeccompService extends Service {
    static final String DESCRIPTOR = "com.warden.controlledsandbox.fixture.INativeEnforcementSeccomp";
    static final int TX_RUN = IBinder.FIRST_CALL_TRANSACTION;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        JSONObject result = runProbe();
        android.util.Log.i("CS_NATIVE_ENF", "SECCOMP_JSON " + result);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                data.enforceInterface(DESCRIPTOR);
                if (code == TX_RUN) {
                    reply.writeNoException();
                    reply.writeString(runProbe().toString());
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
    }

    static JSONObject runProbe() {
        JSONObject out = new JSONObject();
        try {
            int uid = Process.myUid();
            out.put("uid", uid);
            out.put("pid", Process.myPid());
            out.put("isolated", uid >= 99000 && uid <= 99999);
            out.put("processName", processName());
            out.put("abi", NativeEnforcementNative.compiledAbi());
            out.put("jniAvailable", NativeEnforcementNative.available());
            if (!NativeEnforcementNative.available()) {
                out.put("error", NativeEnforcementNative.loadError());
                out.put("classification", "UNVERIFIED_RUNTIME");
                return out;
            }
            JSONObject probe = new JSONObject(NativeEnforcementNative.probeSeccomp());
            out.put("probe", probe);
            JSONObject inner = probe.optJSONObject("result");
            if (inner == null) inner = probe;
            out.put("classification", inner.optString("classification",
                    probe.optString("classification", "UNVERIFIED_RUNTIME")));
            out.put("prctl_no_new_privs", inner.optInt("prctl_no_new_privs", -1));
            out.put("filter_rc", inner.optInt("filter_rc", -1));
            out.put("filter_errno", inner.optInt("filter_errno", -1));
            out.put("signal", inner.optInt("signal", probe.optInt("child_signal", 0)));
            out.put("child_exit", probe.optInt("child_exit", inner.optInt("live_getpid", -1)));
        } catch (Exception error) {
            try {
                out.put("error", error.getClass().getName() + ":" + error.getMessage());
                out.put("classification", "SECCOMP_FILTER_CRASHED");
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName();
        }
        return "unknown";
    }
}
