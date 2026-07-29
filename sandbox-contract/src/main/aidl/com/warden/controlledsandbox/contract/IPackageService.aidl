package com.warden.controlledsandbox.contract;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;

interface IPackageService {
    IPackageManagementSession openManagementSession(in IBinder clientToken);
    IRuntimePermissionSession openRuntimePermissionSession(in IBinder clientToken);
    IVirtualSystemServiceSession openVirtualSystemServiceSession(in IBinder clientToken,
            String packageName, int virtualUserId, int virtualUid, String processName, long generation, String packageRevision);
    boolean startVirtualJob(in VirtualJobParametersSnapshot parameters, IHostJobCallback callback);
    boolean stopVirtualJob(int hostJobId, int stopReason, int internalStopReason,
            String debugStopReason);
}
