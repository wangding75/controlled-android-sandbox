package com.warden.controlledsandbox.framework.core;

import java.util.List;

/** Stable SystemUpdateManager Binder and manager-cache contract for API32/API35. */
public final class SystemUpdateServiceContract {
    public static final String SERVICE_NAME = "system_update";
    public static final List<String> SERVICE_NAMES = List.of(SERVICE_NAME);
    public static final String DESCRIPTOR = "android.os.ISystemUpdateManager";
    public static final String LOGICAL_SERVICE = "systemUpdate";
    public static final String MANAGER_CLASS = "android.os.SystemUpdateManager";
    public static final String MANAGER_SERVICE_FIELD = "mService";

    private SystemUpdateServiceContract() { }

    /**
     * Both API32 and API35 construct SystemUpdateManager through the context service cache and
     * retain the Binder in mService.  The cache must therefore be synchronized when a manager
     * was initialized before the framework hook, while a lazy manager is covered by ServiceManager.
     */
    public static boolean managerCacheRequired(int sdkInt) {
        return sdkInt >= 32;
    }
}
