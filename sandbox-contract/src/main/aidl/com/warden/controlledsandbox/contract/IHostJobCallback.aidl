package com.warden.controlledsandbox.contract;

interface IHostJobCallback {
    void finishHostJob(int hostJobId, boolean needsReschedule);
}
