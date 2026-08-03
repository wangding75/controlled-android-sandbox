package com.warden.controlledsandbox.contract;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;

interface IPackageService {
    // Legacy transaction IDs 1-5 are retained and fail closed without a capability.
    IPackageManagementSession openManagementSession(in IBinder clientToken);
    IRuntimePermissionSession openRuntimePermissionSession(in IBinder clientToken);
    IVirtualSystemServiceSession openVirtualSystemServiceSession(in IBinder clientToken,
            String packageName, int virtualUserId, int virtualUid, String processName,
            long generation, String packageRevision);
    boolean startVirtualJob(in VirtualJobParametersSnapshot parameters, IHostJobCallback callback);
    boolean stopVirtualJob(int hostJobId, int stopReason, int internalStopReason,
            String debugStopReason);

    // Capability bootstrap and capability-aware operations are append-only.
    void registerManagementCapability(in IBinder capability, long capabilityGeneration);
    void registerRuntimeCapability(in IBinder capability, long capabilityGeneration);
    IPackageManagementSession openManagementSessionWithCapability(in IBinder clientToken,
            in IBinder capability, long capabilityGeneration);
    IRuntimePermissionSession openRuntimePermissionSessionWithCapability(in IBinder clientToken,
            in IBinder capability, long capabilityGeneration);
    IVirtualSystemServiceSession openVirtualSystemServiceSessionWithCapability(
            in IBinder clientToken, String packageName, int virtualUserId, int virtualUid,
            String processName, long generation, String packageRevision,
            in IBinder capability, long capabilityGeneration);
    boolean startVirtualJobWithCapability(in VirtualJobParametersSnapshot parameters,
            IHostJobCallback callback, in IBinder capability, long capabilityGeneration);
    boolean stopVirtualJobWithCapability(int hostJobId, int stopReason,
            int internalStopReason, String debugStopReason, in IBinder capability,
            long capabilityGeneration);
}
