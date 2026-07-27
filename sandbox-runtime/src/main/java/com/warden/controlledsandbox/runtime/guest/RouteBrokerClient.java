package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IRuntimeBroker;

public final class RouteBrokerClient {
    public interface Callback { void complete(Bundle route); }

    private RouteBrokerClient() { }

    public static void consume(Activity activity, String token, String sessionId, long generation, Callback callback) {
        call(activity, broker -> broker.consumeRoute(token, sessionId, generation), callback);
    }

    public static void event(Activity activity, Bundle request, Callback callback) {
        call(activity, broker -> broker.activityEvent(request), callback);
    }

    private static void call(Activity activity, RemoteCall call, Callback callback) {
        Intent service = new Intent(activity, RuntimeBrokerService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Bundle result;
                try { result = call.invoke(IRuntimeBroker.Stub.asInterface(binder)); }
                catch (Exception error) { result = failure(error); }
                try { activity.unbindService(this); } catch (Exception ignored) { }
                callback.complete(result);
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        if (!activity.bindService(service, connection, Context.BIND_AUTO_CREATE)) {
            Bundle failed = new Bundle();
            failed.putString(RuntimeKeys.STATUS, "FAILED");
            failed.putString(RuntimeKeys.ERROR_TYPE, "BIND_FAILED");
            failed.putString(RuntimeKeys.ERROR_MESSAGE, "Cannot bind RuntimeBrokerService");
            callback.complete(failed);
        }
    }

    private static Bundle failure(Exception error) {
        Bundle failed = new Bundle();
        failed.putString(RuntimeKeys.STATUS, "FAILED");
        failed.putString(RuntimeKeys.ERROR_TYPE, error.getClass().getName());
        failed.putString(RuntimeKeys.ERROR_MESSAGE, String.valueOf(error.getMessage()));
        return failed;
    }

    private interface RemoteCall { Bundle invoke(IRuntimeBroker broker) throws Exception; }
}
