package com.warden.controlledsandbox.runtime.broker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runtime-Broker-only factory for scoped virtual system-service capability sessions. */
final class RuntimeVirtualSystemServicePackageClient implements AutoCloseable {
    private static final String PACKAGE_SERVICE_CLASS = "com.warden.controlledsandbox.PackageManagementService";
    private final Context context;
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IPackageService root;
    private volatile Exception connectionFailure;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            try { root = IPackageService.Stub.asInterface(service); }
            catch (Exception error) { connectionFailure = error; root = null; }
            finally { connected.countDown(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { root = null; }
    };

    RuntimeVirtualSystemServicePackageClient(Context context) {
        this.context = context.getApplicationContext();
        Intent service = new Intent().setComponent(new ComponentName(
                this.context.getPackageName(), PACKAGE_SERVICE_CLASS));
        if (!this.context.bindService(service, connection, Context.BIND_AUTO_CREATE)) connected.countDown();
    }

    IVirtualSystemServiceSession open(IBinder clientToken, String packageName,
                                      int virtualUserId, int virtualUid, String processName, long generation, String packageRevision) throws Exception {
        if (!connected.await(10, TimeUnit.SECONDS) || root == null) {
            throw new IllegalStateException("Virtual system-service package authority is unavailable",
                    connectionFailure);
        }
        IVirtualSystemServiceSession session = root.openVirtualSystemServiceSession(
                clientToken, packageName, virtualUserId, virtualUid, processName, generation, packageRevision);
        if (session == null) throw new IllegalStateException("Package service returned no virtual system-service session");
        return session;
    }

    @Override public void close() {
        try { context.unbindService(connection); } catch (Exception ignored) { }
        root = null;
    }
}
