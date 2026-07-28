package com.warden.controlledsandbox.contract;

interface IVirtualSystemServiceObserver {
    void onClipboardChanged();
    void onAlarm(String alarmId);
    boolean onJobReady(int guestJobId, in byte[] jobPayload);
}
