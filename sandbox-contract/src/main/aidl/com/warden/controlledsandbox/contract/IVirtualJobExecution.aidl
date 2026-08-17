package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.VirtualJobWorkItemSnapshot;

interface IVirtualJobExecution {
    int guestJobId();
    long generation();
    long dispatchToken();
    boolean isActive();
    void finish(boolean needsReschedule);

    // Appended only: preserve the pre-existing execution transaction IDs.
    VirtualJobWorkItemSnapshot dequeueWork();
    boolean completeWork(int workId);
}
