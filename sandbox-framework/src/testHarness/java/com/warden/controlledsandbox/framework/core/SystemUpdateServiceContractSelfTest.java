package com.warden.controlledsandbox.framework.core;

/** Contract regression test for the API32/API35 SystemUpdateManager entry points. */
public final class SystemUpdateServiceContractSelfTest {
    public static void main(String[] args) {
        require("system_update".equals(SystemUpdateServiceContract.SERVICE_NAME),
                "SystemUpdate service name");
        require("android.os.ISystemUpdateManager".equals(
                        SystemUpdateServiceContract.DESCRIPTOR),
                "SystemUpdate Binder descriptor excludes Stub suffix");
        require(SystemUpdateServiceContract.SERVICE_NAMES.size() == 1
                        && SystemUpdateServiceContract.SERVICE_NAMES.contains("system_update"),
                "bounded SystemUpdate service aliases");
        require("android.os.SystemUpdateManager".equals(
                        SystemUpdateServiceContract.MANAGER_CLASS)
                        && "mService".equals(SystemUpdateServiceContract.MANAGER_SERVICE_FIELD),
                "SystemUpdateManager cache field");
        require(SystemUpdateServiceContract.managerCacheRequired(32)
                        && SystemUpdateServiceContract.managerCacheRequired(35),
                "API32/API35 manager cache compatibility");
        System.out.println("PASS SystemUpdate API32/API35 contract self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
