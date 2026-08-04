package com.warden.controlledsandbox;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import com.warden.controlledsandbox.contract.IPackageAuthorityBootstrap;

/** Non-exported main-process endpoint whose capability is pinned to this process PID. */
public final class HostPackageAuthorityBootstrapService extends Service {
    private final IPackageAuthorityBootstrap.Stub endpoint =
            new IPackageAuthorityBootstrap.Stub() {
                @Override public IBinder capability() {
                    return HostPackageAuthorityCapability.token();
                }

                @Override public int ownerPid() {
                    return Process.myPid();
                }
            };

    @Override public IBinder onBind(Intent intent) {
        return endpoint;
    }
}
