package com.warden.controlledsandbox.contract;

import android.os.Binder;
import android.os.IBinder;

/** Process-owned Binder capability used by the trusted Runtime Broker and Host Job bridge. */
public final class RuntimePackageAuthorityCapability {
    private static final Binder TOKEN = new Binder();
    private static final long GENERATION = PackageAuthorityCapabilityContract.nextGeneration();

    private RuntimePackageAuthorityCapability() { }

    public static IBinder token() { return TOKEN; }
    public static long generation() { return GENERATION; }
}
