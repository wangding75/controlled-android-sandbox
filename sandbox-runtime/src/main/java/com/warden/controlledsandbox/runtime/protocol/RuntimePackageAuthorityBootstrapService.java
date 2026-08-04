package com.warden.controlledsandbox.runtime.protocol;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Non-exported Runtime-process endpoint bound only by PackageManagementService. */
public final class RuntimePackageAuthorityBootstrapService extends Service {
    @Override public IBinder onBind(Intent intent) {
        return RuntimePackageAuthorityCapability.token();
    }
}
