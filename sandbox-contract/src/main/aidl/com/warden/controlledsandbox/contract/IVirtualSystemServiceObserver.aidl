package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.IVirtualJobExecution;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;

interface IVirtualSystemServiceObserver {
    void onClipboardChanged();
    void onDeviceServiceProfileChanged(long policyVersion);
    void onAlarm(in VirtualAlarmSnapshot alarm);
    boolean onJobStart(int guestJobId, in byte[] jobPayload,
            in VirtualJobParametersSnapshot parameters, IVirtualJobExecution execution);
    boolean onJobStop(int guestJobId, in VirtualJobParametersSnapshot parameters);
}
