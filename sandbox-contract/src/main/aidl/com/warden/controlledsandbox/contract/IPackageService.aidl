package com.warden.controlledsandbox.contract;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;

interface IPackageService {
    IPackageManagementSession openManagementSession(in IBinder clientToken);
    IRuntimePermissionSession openRuntimePermissionSession(in IBinder clientToken);
}
