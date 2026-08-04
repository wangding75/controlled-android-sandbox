package com.warden.controlledsandbox.runtime.protocol;

import android.os.Binder;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract;

/** Runtime-process-private Package Authority capability. GuestClassLoader denies this package. */
public final class RuntimePackageAuthorityCapability {
    private static final Binder TOKEN = new Binder();

    private RuntimePackageAuthorityCapability() { }

    public static IBinder token() { return TOKEN; }
    public static long epochMarker() { return PackageAuthorityCapabilityContract.SERVER_MANAGED_EPOCH; }
}
