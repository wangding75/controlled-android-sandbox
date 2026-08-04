package com.warden.controlledsandbox.contract;

import android.os.IBinder;

/** Private fixed-component endpoint that reports the capability owner process. */
interface IPackageAuthorityBootstrap {
    IBinder capability();
    int ownerPid();
}
