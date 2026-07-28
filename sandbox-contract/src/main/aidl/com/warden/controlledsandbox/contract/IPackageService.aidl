package com.warden.controlledsandbox.contract;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;

interface IPackageService {
    IPackageManagementSession openManagementSession(in IBinder clientToken);
    IRuntimePermissionSession openRuntimePermissionSession(in IBinder clientToken);
    IVirtualSystemServiceSession openVirtualSystemServiceSession(in IBinder clientToken, String packageName, int virtualUserId, String processName, long generation);
}
