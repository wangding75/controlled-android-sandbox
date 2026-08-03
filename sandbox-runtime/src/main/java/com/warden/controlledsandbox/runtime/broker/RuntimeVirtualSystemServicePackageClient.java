package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.contract.RuntimePackageAuthorityCapability;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.runtime.protocol.RebindableServiceConnector;

/** Runtime-Broker-only factory for scoped virtual system-service capability sessions. */
final class RuntimeVirtualSystemServicePackageClient implements AutoCloseable {
    private static final String PACKAGE_SERVICE_CLASS = "com.warden.controlledsandbox.PackageManagementService";
    private final Context context;
    private final RebindableServiceConnector<IPackageService> rootConnection;

    RuntimeVirtualSystemServicePackageClient(Context context) {
        this.context = context.getApplicationContext();
        Intent service = new Intent().setComponent(new ComponentName(
                RuntimePeerPolicy.hostPackageFor(this.context), PACKAGE_SERVICE_CLASS));
        this.rootConnection = new RebindableServiceConnector<>(this.context, service,
                IPackageService.Stub::asInterface, ignored -> { },
                "Virtual system-service package authority");
    }

    IVirtualSystemServiceSession open(IBinder clientToken, String packageName,
                                      int virtualUserId, int virtualUid, String processName, long generation, String packageRevision) throws Exception {
        IPackageService root = rootConnection.require();
        root.registerRuntimeCapability(RuntimePackageAuthorityCapability.token(),
                RuntimePackageAuthorityCapability.generation());
        IVirtualSystemServiceSession session = root.openVirtualSystemServiceSessionWithCapability(
                clientToken, packageName, virtualUserId, virtualUid, processName, generation,
                packageRevision, RuntimePackageAuthorityCapability.token(),
                RuntimePackageAuthorityCapability.generation());
        if (session == null) throw new IllegalStateException("Package service returned no virtual system-service session");
        return session;
    }

    @Override public void close() {
        rootConnection.close();
    }
}
