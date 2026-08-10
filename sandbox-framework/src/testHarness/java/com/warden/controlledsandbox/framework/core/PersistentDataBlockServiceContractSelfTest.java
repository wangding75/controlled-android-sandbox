package com.warden.controlledsandbox.framework.core;

/** Contract regression test for the API32/API35 PersistentDataBlock entry points. */
public final class PersistentDataBlockServiceContractSelfTest {
    public static void main(String[] args) {
        require("persistent_data_block".equals(
                        PersistentDataBlockServiceContract.SERVICE_NAME),
                "PersistentDataBlock service name");
        require("android.service.persistentdata.IPersistentDataBlockService".equals(
                        PersistentDataBlockServiceContract.DESCRIPTOR),
                "PersistentDataBlock Binder descriptor excludes Stub suffix");
        require(PersistentDataBlockServiceContract.SERVICE_NAMES.size() == 1
                        && PersistentDataBlockServiceContract.SERVICE_NAMES.contains(
                                "persistent_data_block"),
                "bounded PersistentDataBlock service aliases");
        require("android.service.persistentdata.PersistentDataBlockManager".equals(
                        PersistentDataBlockServiceContract.MANAGER_CLASS)
                        && "sService".equals(
                                PersistentDataBlockServiceContract.MANAGER_SERVICE_FIELD),
                "PersistentDataBlockManager cache field");
        require(!PersistentDataBlockServiceContract.managerCacheRequired(32)
                        && PersistentDataBlockServiceContract.managerCacheRequired(35),
                "API32/API35 manager cache compatibility");
        System.out.println("PASS PersistentDataBlock API32/API35 contract self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
