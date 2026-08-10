package com.warden.controlledsandbox.framework.core;

import java.util.List;

/** Stable Android Context Hub framework contract shared by the hook and runtime diagnostics. */
public final class ContextHubServiceContract {
    public static final String SERVICE_NAME = "contexthub";
    public static final List<String> SERVICE_NAMES = List.of(SERVICE_NAME);
    public static final String DESCRIPTOR = "android.hardware.location.IContextHubService";
    public static final String LOGICAL_SERVICE = "contexthub";
    public static final String MANAGER_CLASS = "android.hardware.location.ContextHubManager";
    public static final String MANAGER_SERVICE_FIELD = "mService";

    private ContextHubServiceContract() { }
}
