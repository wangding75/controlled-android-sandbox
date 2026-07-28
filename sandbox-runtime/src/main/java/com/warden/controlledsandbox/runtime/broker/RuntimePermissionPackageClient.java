package com.warden.controlledsandbox.runtime.broker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runtime-Broker-only client for the Package Service permission capability. */
final class RuntimePermissionPackageClient implements RuntimePermissionGateway {
    private static final String PACKAGE_SERVICE_CLASS =
            "com.warden.controlledsandbox.PackageManagementService";
    private final Context context;
    private final Binder clientToken = new Binder();
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IRuntimePermissionSession session;
    private volatile Exception connectionFailure;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                IPackageService root = IPackageService.Stub.asInterface(service);
                session = root == null ? null : root.openRuntimePermissionSession(clientToken);
            } catch (Exception error) {
                connectionFailure = error;
                session = null;
            } finally {
                connected.countDown();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { session = null; }
    };

    RuntimePermissionPackageClient(Context context) {
        this.context = context.getApplicationContext();
        Intent service = new Intent().setComponent(new ComponentName(
                this.context.getPackageName(), PACKAGE_SERVICE_CLASS));
        if (!this.context.bindService(service, connection, Context.BIND_AUTO_CREATE)) {
            connected.countDown();
        }
    }

    @Override public PackageServiceResult request(String packageName, int virtualUserId, String permission,
                                 int requestCode, String sessionId, long generation)
            throws Exception {
        return requireSuccess(requireSession().requestRuntimePermission(packageName, virtualUserId,
                permission, requestCode, sessionId, generation));
    }

    @Override public PackageServiceResult report(String packageName, int virtualUserId, String permission,
                                int requestCode, String sessionId, long generation,
                                boolean hostGranted, String reason) throws Exception {
        return requireSuccess(requireSession().reportRuntimePermissionResult(packageName,
                virtualUserId, permission, requestCode, sessionId, generation,
                hostGranted, reason));
    }

    private IRuntimePermissionSession requireSession() throws Exception {
        if (!connected.await(10, TimeUnit.SECONDS) || session == null) {
            throw new IllegalStateException("Runtime permission package service is unavailable",
                    connectionFailure);
        }
        return session;
    }

    private static PackageServiceResult requireSuccess(PackageServiceResult result) {
        if (result == null) throw new IllegalStateException("Package service returned no result");
        if (!result.successful()) {
            throw new IllegalStateException(result.errorCode() + ": " + result.errorMessage());
        }
        return result;
    }

    @Override public void close() {
        IRuntimePermissionSession current = session;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
        try { context.unbindService(connection); } catch (Exception ignored) { }
        session = null;
    }
}
