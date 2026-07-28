package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.IVirtualJobExecution;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;

interface IVirtualSystemServiceObserver {
    void onClipboardChanged();
    void onAlarm(String alarmId);
    boolean onJobStart(int guestJobId, in byte[] jobPayload,
            in VirtualJobParametersSnapshot parameters, IVirtualJobExecution execution);
    boolean onJobStop(int guestJobId, in VirtualJobParametersSnapshot parameters);
}
