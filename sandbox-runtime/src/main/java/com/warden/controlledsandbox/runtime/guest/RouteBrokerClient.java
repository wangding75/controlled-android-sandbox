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
import com.warden.controlledsandbox.contract.PackageServiceResult;

public final class RouteBrokerClient {
    public interface Callback { void complete(Bundle route); }
    public interface PermissionCallback { void complete(PackageServiceResult result); }

    private RouteBrokerClient() { }

    public static void consume(Activity activity, String token, String sessionId, long generation, Callback callback) {
        call(activity, broker -> broker.consumeRoute(token, sessionId, generation), callback);
    }

    public static void event(Activity activity, Bundle request, Callback callback) {
        call(activity, broker -> broker.activityEvent(request), callback);
    }

    public static void launchActivity(Context context, Bundle request, Callback callback) {
        call(context, broker -> broker.launchActivity(request), callback);
    }

    public static void invokeComponent(Context context, Bundle request, Callback callback) {
        call(context, broker -> broker.invokeComponent(request), callback);
    }

    public static void requestPermission(Activity activity, String sessionId, long generation,
                                         String permission, int requestCode,
                                         PermissionCallback callback) {
        permissionCall(activity, broker -> broker.requestRuntimePermission(
                sessionId, generation, permission, requestCode), callback);
    }

    public static void reportPermissionResult(Activity activity, String sessionId, long generation,
                                               String permission, int requestCode,
                                               boolean hostGranted, String reason,
                                               PermissionCallback callback) {
        permissionCall(activity, broker -> broker.reportRuntimePermissionResult(
                sessionId, generation, permission, requestCode, hostGranted, reason), callback);
    }

    private static void permissionCall(Activity activity, PermissionRemoteCall call,
                                       PermissionCallback callback) {
        Intent service = new Intent(activity, RuntimeBrokerService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                PackageServiceResult result;
                try { result = call.invoke(IRuntimeBroker.Stub.asInterface(binder)); }
                catch (Exception error) { result = PackageServiceResult.failure(
                        "runtimePermission", error.getClass().getSimpleName(),
                        String.valueOf(error.getMessage())); }
                try { activity.unbindService(this); } catch (Exception ignored) { }
                callback.complete(result);
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        if (!activity.bindService(service, connection, Context.BIND_AUTO_CREATE)) {
            callback.complete(PackageServiceResult.failure("runtimePermission",
                    "BIND_FAILED", "Cannot bind RuntimeBrokerService"));
        }
    }

    private static void call(Context context, RemoteCall call, Callback callback) {
        Intent service = new Intent(context, RuntimeBrokerService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Bundle result;
                try { result = call.invoke(IRuntimeBroker.Stub.asInterface(binder)); }
                catch (Exception error) { result = failure(error); }
                try { context.unbindService(this); } catch (Exception ignored) { }
                callback.complete(result);
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        if (!context.bindService(service, connection, Context.BIND_AUTO_CREATE)) {
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
    private interface PermissionRemoteCall {
        PackageServiceResult invoke(IRuntimeBroker broker) throws Exception;
    }
}
