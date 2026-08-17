package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.VirtualJobWorkItemSnapshot;

interface IHostJobCallback {
    void finishHostJob(int hostJobId, boolean needsReschedule);

    // Appended only: the trusted Host JobService owns the real JobParameters.
    VirtualJobWorkItemSnapshot dequeueHostWork(int hostJobId);
    boolean completeHostWork(int hostJobId, int workId);
}
