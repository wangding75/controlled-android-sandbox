package com.warden.controlledsandbox.contract;

interface IVirtualJobExecution {
    int guestJobId();
    long generation();
    long dispatchToken();
    boolean isActive();
    void finish(boolean needsReschedule);
}
