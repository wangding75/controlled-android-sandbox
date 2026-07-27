package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.PackageCatalogSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Main-process client for the Binder-owned package authority. */
final class PackageServiceClient implements AutoCloseable {
    private final Context context;
    private final Binder clientToken = new Binder();
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IPackageManagementSession session;
    private volatile Exception connectionFailure;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                IPackageService root = IPackageService.Stub.asInterface(service);
                session = root == null ? null : root.openManagementSession(clientToken);
            } catch (Exception error) {
                connectionFailure = error;
                session = null;
            } finally {
                connected.countDown();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { session = null; }
    };

    PackageServiceClient(Context context) {
        this.context = context.getApplicationContext();
        boolean bound = this.context.bindService(
                new Intent(this.context, PackageManagementService.class),
                connection, Context.BIND_AUTO_CREATE);
        if (!bound) connected.countDown();
    }

    SandboxCatalogState load() throws Exception {
        PackageCatalogSnapshot snapshot = requireSuccess(requireSession().loadCatalog()).catalog();
        if (snapshot == null) throw new IllegalStateException("Package service returned no catalog");
        return PackageServiceMapper.fromSnapshot(snapshot);
    }

    SandboxRecord importApk(Uri uri) throws Exception {
        return record(requireSession().importApk(uri == null ? "" : uri.toString()));
    }

    SandboxRecord importApkFile(File source) throws Exception {
        return record(requireSession().importApkFile(source == null ? "" : source.getAbsolutePath()));
    }

    SandboxRecord findRecord(String packageName) throws Exception {
        PackageServiceResult result = requireSuccess(requireSession().findRecord(packageName));
        PackageRecordSnapshot record = result.record();
        return record == null ? null : PackageServiceMapper.fromSnapshot(record);
    }

    void ensureInstance(String packageName, int virtualUserId) throws Exception {
        requireSuccess(requireSession().ensureInstance(packageName, virtualUserId));
    }

    int createClone(String packageName) throws Exception {
        return requireSuccess(requireSession().createClone(packageName)).intValue();
    }

    void updateInstanceStatus(String packageName, int virtualUserId, String status) throws Exception {
        requireSuccess(requireSession().updateInstanceStatus(packageName, virtualUserId, status));
    }

    SandboxCatalogState deleteInstance(String packageName, int virtualUserId) throws Exception {
        PackageCatalogSnapshot snapshot = requireSuccess(
                requireSession().deleteInstance(packageName, virtualUserId)).catalog();
        if (snapshot == null) throw new IllegalStateException("Package service returned no catalog");
        return PackageServiceMapper.fromSnapshot(snapshot);
    }

    String maintenanceWarning() throws Exception {
        return requireSuccess(requireSession().maintenanceStatus()).textValue();
    }

    private SandboxRecord record(PackageServiceResult raw) throws Exception {
        PackageRecordSnapshot record = requireSuccess(raw).record();
        if (record == null) throw new IllegalStateException("Package service returned no package record");
        return PackageServiceMapper.fromSnapshot(record);
    }

    private IPackageManagementSession requireSession() throws Exception {
        if (!connected.await(10, TimeUnit.SECONDS) || session == null) {
            Exception failure = connectionFailure;
            throw new IllegalStateException("Package management service is unavailable"
                    + (failure == null ? "" : ": " + failure.getMessage()), failure);
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
        IPackageManagementSession current = session;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
        try { context.unbindService(connection); } catch (Exception ignored) { }
        session = null;
    }
}
