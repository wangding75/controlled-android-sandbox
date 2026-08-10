package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.PackageServiceResult;

public final class RouteBrokerClient {
    public interface Callback { void complete(Bundle route); }
    public interface PermissionCallback { void complete(PackageServiceResult result); }
    public interface ActivityResultCallback { void complete(ActivityResultResult result); }

    private RouteBrokerClient() { }

    public static void consume(Activity activity, String token, String sessionId, long generation, Callback callback) {
        call(activity, broker -> execute(broker, RuntimeOperationRequest.CONSUME_ROUTE, routeRequest(token, sessionId, generation)), callback);
    }

    public static void event(Activity activity, Bundle request, Callback callback) {
        call(activity, broker -> execute(broker, RuntimeOperationRequest.ACTIVITY_EVENT, request), callback);
    }

    public static void launchActivity(Context context, Bundle request, Callback callback) {
        call(context, broker -> execute(broker, RuntimeOperationRequest.LAUNCH_ACTIVITY, request), callback);
    }

    public static void invokeComponent(Context context, Bundle request, Callback callback) {
        call(context, broker -> execute(broker, RuntimeOperationRequest.INVOKE_COMPONENT, request), callback);
    }

    public static void activityResultOperation(
            Context context,
            ActivityResultRequest request,
            ActivityResultCallback callback) {
        activityResultCall(context, broker -> broker.activityResultOperation(request), callback);
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

    private static void activityResultCall(
            Context context,
            ActivityResultRemoteCall call,
            ActivityResultCallback callback) {
        Context bindingContext = bindingContext(context);
        Intent service = new Intent(context, RuntimeBrokerService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                ActivityResultResult result;
                try { result = call.invoke(IRuntimeBroker.Stub.asInterface(binder)); }
                catch (Exception error) {
                    result = com.warden.controlledsandbox.contract.ActivityResultResult.failure(
                            com.warden.controlledsandbox.domain.protocol.RuntimeProtocol.CURRENT,
                            "activity-result-bind-failure",
                            new com.warden.controlledsandbox.contract.SandboxError(
                                    "ACTIVITY_RESULT_BINDER_FAILURE",
                                    error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()),
                                    true));
                }
                try { bindingContext.unbindService(this); } catch (Exception ignored) { }
                callback.complete(result);
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        if (!bindingContext.bindService(service, connection, Context.BIND_AUTO_CREATE)) {
            callback.complete(com.warden.controlledsandbox.contract.ActivityResultResult.failure(
                    com.warden.controlledsandbox.domain.protocol.RuntimeProtocol.CURRENT,
                    "activity-result-bind-failure",
                    new com.warden.controlledsandbox.contract.SandboxError(
                            "BIND_FAILED", "Cannot bind RuntimeBrokerService", true)));
        }
    }

    private static void permissionCall(Activity activity, PermissionRemoteCall call,
                                       PermissionCallback callback) {
        Context bindingContext = bindingContext(activity);
        Intent service = new Intent(activity, RuntimeBrokerService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                PackageServiceResult result;
                try { result = call.invoke(IRuntimeBroker.Stub.asInterface(binder)); }
                catch (Exception error) { result = PackageServiceResult.failure(
                        "runtimePermission", error.getClass().getSimpleName(),
                        String.valueOf(error.getMessage())); }
                try { bindingContext.unbindService(this); } catch (Exception ignored) { }
                callback.complete(result);
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        if (!bindingContext.bindService(service, connection, Context.BIND_AUTO_CREATE)) {
            callback.complete(PackageServiceResult.failure("runtimePermission",
                    "BIND_FAILED", "Cannot bind RuntimeBrokerService"));
        }
    }

    private static void call(Context context, RemoteCall call, Callback callback) {
        Context bindingContext = bindingContext(context);
        Intent service = new Intent(context, RuntimeBrokerService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Bundle result;
                try { result = call.invoke(IRuntimeBroker.Stub.asInterface(binder)); }
                catch (Exception error) { result = failure(error); }
                try { bindingContext.unbindService(this); } catch (Exception ignored) { }
                callback.complete(result);
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        if (!bindingContext.bindService(service, connection, Context.BIND_AUTO_CREATE)) {
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

    /**
     * Broker calls can outlive a StubActivity during pause/stop/destroy.  Keep the binding owned
     * by the Guest application context so an in-flight callback cannot leak an Activity-bound
     * ServiceConnection.  The original context still builds the explicit Broker component, so
     * the Guest package and route policy are unchanged.
     */
    private static Context bindingContext(Context context) {
        Context application = context.getApplicationContext();
        return application == null ? context : application;
    }

    private static Bundle execute(IRuntimeBroker broker, String operation, Bundle payload)
            throws Exception {
        return RuntimeOperationTransport.toLegacyBundle(
                RuntimeOperationTransport.execute(broker, operation, payload));
    }

    private static Bundle routeRequest(String token, String sessionId, long generation) {
        Bundle request = new Bundle();
        request.putInt(RuntimeKeys.PROTOCOL,
                com.warden.controlledsandbox.domain.protocol.RuntimeProtocol.CURRENT);
        request.putString(RuntimeKeys.ROUTE_TOKEN, token);
        request.putString(RuntimeKeys.SESSION_ID, sessionId);
        request.putLong(RuntimeKeys.GENERATION, generation);
        return request;
    }

    private interface RemoteCall { Bundle invoke(IRuntimeBroker broker) throws Exception; }
    private interface PermissionRemoteCall {
        PackageServiceResult invoke(IRuntimeBroker broker) throws Exception;
    }
    private interface ActivityResultRemoteCall {
        ActivityResultResult invoke(IRuntimeBroker broker) throws Exception;
    }
}
