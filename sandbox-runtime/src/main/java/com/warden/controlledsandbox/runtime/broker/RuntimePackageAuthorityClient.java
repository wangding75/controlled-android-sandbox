package com.warden.controlledsandbox.runtime.broker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import com.warden.controlledsandbox.contract.IPackageRuntimeQuerySession;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.runtime.protocol.RebindableServiceConnector;
import com.warden.controlledsandbox.runtime.protocol.RuntimePackageAuthorityCapability;
import com.warden.controlledsandbox.runtime.protocol.RuntimePackageAuthorityRecovery;

/**
 * Broker-only read capability for trusted installed-package and virtual-user state.
 *
 * <p>The Guest process may carry a metadata projection for PackageManager queries, but a
 * cross-package execution route must not trust that projection as executable authority. The
 * Broker re-reads the package record and virtual package state from the package authority before
 * allocating a target session. This keeps APK paths, split artifacts, native policy and enabled
 * state on the same authority used by install/clear/delete transactions.</p>
 */
final class RuntimePackageAuthorityClient implements AutoCloseable {
    private static final String PACKAGE_SERVICE_CLASS =
            "com.warden.controlledsandbox.PackageManagementService";

    private final Binder clientToken = new Binder();
    private final RebindableServiceConnector<IPackageRuntimeQuerySession> sessionConnection;

    RuntimePackageAuthorityClient(Context context) {
        Context application = context.getApplicationContext();
        Intent service = new Intent().setComponent(new ComponentName(
                RuntimePeerPolicy.hostPackageFor(application), PACKAGE_SERVICE_CLASS));
        sessionConnection = new RebindableServiceConnector<>(application, service, binder -> {
            IPackageService root = IPackageService.Stub.asInterface(binder);
            if (root == null) return null;
            IPackageRuntimeQuerySession session;
            try {
                session = root.openRuntimePackageQuerySessionWithCapability(
                        clientToken, RuntimePackageAuthorityCapability.token(),
                        RuntimePackageAuthorityCapability.epochMarker());
            } catch (SecurityException capabilityFailure) {
                if (!RuntimePackageAuthorityRecovery.isCapabilityFailure(capabilityFailure)) {
                    throw capabilityFailure;
                }
                RuntimePackageAuthorityRecovery.register(root);
                session = root.openRuntimePackageQuerySessionWithCapability(
                        clientToken, RuntimePackageAuthorityCapability.token(),
                        RuntimePackageAuthorityCapability.epochMarker());
            }
            if (session == null) throw new IllegalStateException(
                    "Package service returned no runtime query session");
            return session;
        }, IPackageRuntimeQuerySession::close, "Runtime package authority",
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT | Context.BIND_ABOVE_CLIENT);
    }

    PackageRecordSnapshot findRecord(String packageName) throws Exception {
        return requireSuccess(requireSession().findRecord(packageName)).record();
    }

    VirtualPackageStateSnapshot virtualPackageState(String packageName, int virtualUserId)
            throws Exception {
        return requireSuccess(requireSession().getVirtualPackageState(packageName, virtualUserId))
                .packageState();
    }

    private IPackageRuntimeQuerySession requireSession() throws Exception {
        return sessionConnection.require();
    }

    private static PackageServiceResult requireSuccess(PackageServiceResult result) {
        if (result == null) throw new IllegalStateException("Package authority returned no result");
        if (!result.successful()) {
            throw new IllegalStateException(result.errorCode() + ": " + result.errorMessage());
        }
        return result;
    }

    @Override public void close() { sessionConnection.close(); }
}
