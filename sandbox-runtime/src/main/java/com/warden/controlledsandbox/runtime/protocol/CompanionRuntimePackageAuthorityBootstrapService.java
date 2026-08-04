package com.warden.controlledsandbox.runtime.protocol;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import com.warden.controlledsandbox.contract.IPackageAuthorityBootstrap;

/** Signature-protected Companion endpoint whose capability is pinned to sandbox_server32. */
public final class CompanionRuntimePackageAuthorityBootstrapService extends Service {
    private final IPackageAuthorityBootstrap.Stub endpoint =
            new IPackageAuthorityBootstrap.Stub() {
                @Override public IBinder capability() {
                    return RuntimePackageAuthorityCapability.token();
                }

                @Override public int ownerPid() {
                    return Process.myPid();
                }
            };

    @Override public IBinder onBind(Intent intent) {
        return endpoint;
    }
}
