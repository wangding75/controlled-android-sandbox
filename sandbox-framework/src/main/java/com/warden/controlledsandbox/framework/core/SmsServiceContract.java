package com.warden.controlledsandbox.framework.core;

import java.util.List;

/** Stable Android platform contract for the framework SMS Binder surface. */
public final class SmsServiceContract {
    public static final String DESCRIPTOR = "com.android.internal.telephony.ISms";
    public static final String LOGICAL_SERVICE = "isms";
    public static final List<String> SERVICE_NAMES = List.of("isms", "isms2", "isms_msim");
    public static final String TELEPHONY_MANAGER_CACHE_FIELD = "sISms";

    private SmsServiceContract() { }
}
