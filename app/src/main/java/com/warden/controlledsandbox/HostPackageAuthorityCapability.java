package com.warden.controlledsandbox;

import android.os.Binder;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract;

/** Main-process-owned Binder capability for Package Management authority. */
final class HostPackageAuthorityCapability {
    private static final Binder TOKEN = new Binder();
    private static final long GENERATION = PackageAuthorityCapabilityContract.nextGeneration();

    private HostPackageAuthorityCapability() { }

    static IBinder token() { return TOKEN; }
    static long generation() { return GENERATION; }
}
