package com.warden.controlledsandbox;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Non-exported main-process endpoint bound only by PackageManagementService. */
public final class HostPackageAuthorityBootstrapService extends Service {
    @Override public IBinder onBind(Intent intent) {
        return HostPackageAuthorityCapability.token();
    }
}
