package com.warden.controlledsandbox.contract;

oneway interface IProviderObserver {
    void onChange(String uri, boolean selfChange, int flags);
}
