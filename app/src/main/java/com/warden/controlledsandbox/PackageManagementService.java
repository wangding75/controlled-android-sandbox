package com.warden.controlledsandbox;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Lifecycle owner for the dedicated Package Service process. */
public final class PackageManagementService extends Service {
    private PackageServiceDependencies dependencies;
    private PackageServiceBinder binder;
    private PackageAuthorityBootstrapConnections bootstrapConnections;

    @Override public void onCreate() {
        super.onCreate();
        dependencies = PackageServiceDependencies.create(this, getFilesDir());
        binder = new PackageServiceBinder(dependencies);
        bootstrapConnections = new PackageAuthorityBootstrapConnections(
                this, dependencies.capabilityRegistry);
        bootstrapConnections.start();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        if (bootstrapConnections != null) bootstrapConnections.close();
        bootstrapConnections = null;
        if (dependencies != null) dependencies.close();
        binder = null;
        super.onDestroy();
    }
}
