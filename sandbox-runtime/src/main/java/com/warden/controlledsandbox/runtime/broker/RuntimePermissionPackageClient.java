package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.contract.RuntimePackageAuthorityCapability;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.runtime.protocol.RebindableServiceConnector;

/** Runtime-Broker-only client for the Package Service permission capability. */
final class RuntimePermissionPackageClient implements RuntimePermissionGateway {
    private static final String PACKAGE_SERVICE_CLASS =
            "com.warden.controlledsandbox.PackageManagementService";
    private final Context context;
    private final Binder clientToken = new Binder();
    private final RebindableServiceConnector<IRuntimePermissionSession> sessionConnection;

    RuntimePermissionPackageClient(Context context) {
        this.context = context.getApplicationContext();
        Intent service = new Intent().setComponent(new ComponentName(
                RuntimePeerPolicy.hostPackageFor(this.context), PACKAGE_SERVICE_CLASS));
        this.sessionConnection = new RebindableServiceConnector<>(this.context, service, binder -> {
            IPackageService root = IPackageService.Stub.asInterface(binder);
            if (root == null) return null;
            root.registerRuntimeCapability(RuntimePackageAuthorityCapability.token(),
                    RuntimePackageAuthorityCapability.generation());
            return root.openRuntimePermissionSessionWithCapability(clientToken,
                    RuntimePackageAuthorityCapability.token(),
                    RuntimePackageAuthorityCapability.generation());
        }, IRuntimePermissionSession::close, "Runtime permission package service");
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
        return sessionConnection.require();
    }

    private static PackageServiceResult requireSuccess(PackageServiceResult result) {
        if (result == null) throw new IllegalStateException("Package service returned no result");
        if (!result.successful()) {
            throw new IllegalStateException(result.errorCode() + ": " + result.errorMessage());
        }
        return result;
    }

    @Override public void close() {
        sessionConnection.close();
    }
}
