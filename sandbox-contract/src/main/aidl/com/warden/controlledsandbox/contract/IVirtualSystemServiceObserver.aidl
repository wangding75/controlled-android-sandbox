package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.IVirtualJobExecution;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;

interface IVirtualSystemServiceObserver {
    void onClipboardChanged();
    void onDeviceServiceProfileChanged(long policyVersion);
    void onInteractionProfileChanged(long policyVersion);
    void onNetworkServiceProfileChanged(long policyVersion);
    void onApplicationEnvironmentProfileChanged(long policyVersion);
    void onCompatibilityProfileChanged(long policyVersion);
    void onPolicyServicesProfileChanged(long policyVersion);
    void onApplicationEnvironmentDataChanged(String domain, String key);
    void onAlarm(in VirtualAlarmSnapshot alarm);
    boolean onJobStart(int guestJobId, in byte[] jobPayload,
            in VirtualJobParametersSnapshot parameters, IVirtualJobExecution execution);
    boolean onJobStop(int guestJobId, in VirtualJobParametersSnapshot parameters);
}
