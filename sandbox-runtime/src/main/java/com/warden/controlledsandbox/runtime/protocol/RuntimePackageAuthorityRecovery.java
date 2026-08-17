package com.warden.controlledsandbox.runtime.protocol;

import android.os.RemoteException;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract;

/**
 * Re-establishes the runtime capability when Package Service survives the Runtime process.
 *
 * <p>The bootstrap ServiceConnection remains the normal source of authority.  This small
 * caller-side handshake closes the Android binding notification race: the trusted runtime
 * process presents its process-owned Binder, and Package Service applies the same UID/PID,
 * death-link and role checks as the bootstrap connection.</p>
 */
public final class RuntimePackageAuthorityRecovery {
    private RuntimePackageAuthorityRecovery() { }

    public static boolean isCapabilityFailure(SecurityException error) {
        if (error == null || error.getMessage() == null) return false;
        String message = error.getMessage();
        return message.contains("PACKAGE_RUNTIME_CAPABILITY_DENIED")
                || message.contains("PACKAGE_AUTHORITY_BOOTSTRAP");
    }

    public static void register(IPackageService root) throws RemoteException {
        if (root == null) throw new IllegalArgumentException("package service is required");
        root.registerRuntimeCapability(RuntimePackageAuthorityCapability.token(),
                PackageAuthorityCapabilityContract.SERVER_MANAGED_EPOCH);
    }
}
