package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Direct fixture entry for 32/64-bit isolated seccomp feasibility. */
public final class NativeEnforcementSeccompActivity extends Activity {
    private static final String TAG = "CS_NATIVE_ENF";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            JSONObject started = new JSONObject();
            started.put("status", "STARTED");
            persist(started);
        } catch (Exception ignored) { }
        new Thread(this::runCampaign, "native-enf-seccomp").start();
    }

    private void runCampaign() {
        JSONObject result = new JSONObject();
        ServiceConnection connection = null;
        try {
            result.put("schema", "t57-r03-p0a-02-seccomp");
            JSONObject host = new JSONObject();
            host.put("uid", Process.myUid());
            host.put("pid", Process.myPid());
            host.put("processName", processName());
            host.put("packageName", getPackageName());
            result.put("host", host);
            BindState bind = new BindState();
            CountDownLatch latch = new CountDownLatch(1);
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    bind.binder = service;
                    latch.countDown();
                }

                @Override public void onServiceDisconnected(ComponentName name) { }
            };
            boolean ok = bindService(new Intent(this, NativeEnforcementSeccompService.class),
                    connection, Context.BIND_AUTO_CREATE);
            if (!ok || !latch.await(20, TimeUnit.SECONDS) || bind.binder == null) {
                result.put("status", "ERROR");
                result.put("error", "ISOLATED_BIND_FAILED");
            } else {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(NativeEnforcementSeccompService.DESCRIPTOR);
                    bind.binder.transact(NativeEnforcementSeccompService.TX_RUN, data, reply, 0);
                    reply.readException();
                    JSONObject isolated = new JSONObject(reply.readString());
                    result.put("isolated", isolated);
                    result.put("isolatedUidDistinct",
                            isolated.optInt("uid", -1) != Process.myUid()
                                    && isolated.optBoolean("isolated", false));
                    result.put("classification", isolated.optString("classification"));
                    result.put("status", "PASS");
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        } catch (Exception error) {
            try {
                result.put("status", "ERROR");
                result.put("error", error.getClass().getName() + ":" + error.getMessage());
            } catch (Exception ignored) { }
            Log.e(TAG, "seccomp activity failed", error);
        } finally {
            if (connection != null) {
                try { unbindService(connection); } catch (Exception ignored) { }
            }
            persist(result);
            Log.i(TAG, "SECCOMP_RESULT " + result);
            runOnUiThread(this::finish);
        }
    }

    private void persist(JSONObject result) {
        try (FileOutputStream output = new FileOutputStream(
                new File(getFilesDir(), "native-enf-seccomp.json"))) {
            output.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "cannot persist seccomp result", error);
        }
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName();
        }
        return "unknown";
    }

    private static final class BindState {
        IBinder binder;
    }
}
