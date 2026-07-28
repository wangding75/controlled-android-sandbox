package com.warden.controlledsandbox.contract;

oneway interface IVirtualSystemServiceObserver {
    void onClipboardChanged();
    void onAlarm(String alarmId);
}
