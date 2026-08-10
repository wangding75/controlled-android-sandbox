package com.warden.controlledsandbox.framework.core;

import java.util.List;

/** Stable PersistentDataBlock Binder and manager-cache contract for API32/API35. */
public final class PersistentDataBlockServiceContract {
    public static final String SERVICE_NAME = "persistent_data_block";
    public static final List<String> SERVICE_NAMES = List.of(SERVICE_NAME);
    public static final String DESCRIPTOR =
            "android.service.persistentdata.IPersistentDataBlockService";
    public static final String LOGICAL_SERVICE = "persistentDataBlock";
    public static final String MANAGER_CLASS =
            "android.service.persistentdata.PersistentDataBlockManager";
    public static final String MANAGER_SERVICE_FIELD = "sService";

    private PersistentDataBlockServiceContract() { }

    /**
     * PersistentDataBlockManager became a registered framework service in API35.  API32 still
     * has the hidden Binder contract on some builds, but no stable Context manager/cache entry;
     * the ServiceManager projection is therefore the only supported API32 binding.
     */
    public static boolean managerCacheRequired(int sdkInt) {
        return sdkInt >= 35;
    }
}
