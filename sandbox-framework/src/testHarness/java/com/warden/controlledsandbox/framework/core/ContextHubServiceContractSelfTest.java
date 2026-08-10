package com.warden.controlledsandbox.framework.core;

/** Contract regression test for the shared API32/API35 Context Hub entry point. */
public final class ContextHubServiceContractSelfTest {
    public static void main(String[] args) {
        require("contexthub".equals(ContextHubServiceContract.SERVICE_NAME),
                "Context Hub service name");
        require("android.hardware.location.IContextHubService".equals(
                        ContextHubServiceContract.DESCRIPTOR),
                "Context Hub Binder descriptor excludes Stub suffix");
        require(ContextHubServiceContract.SERVICE_NAMES.size() == 1
                        && ContextHubServiceContract.SERVICE_NAMES.contains("contexthub"),
                "bounded service aliases");
        require("android.hardware.location.ContextHubManager".equals(
                        ContextHubServiceContract.MANAGER_CLASS)
                        && "mService".equals(ContextHubServiceContract.MANAGER_SERVICE_FIELD),
                "ContextHubManager cache field");
        System.out.println("PASS Context Hub API32/API35 contract self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
