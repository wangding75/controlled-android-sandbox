package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import java.util.Map;

/** Fail-closed readiness for privileged environment service proxies. */
final class PrivilegedServicesProxyReadiness {
    private PrivilegedServicesProxyReadiness() { }

    static void require(
            Map<String, Boolean> installed, VirtualPrivilegedServicesProfileSnapshot profile) {
        if (profile == null) throw new IllegalStateException("VIRTUAL_PRIVILEGED_SERVICES_PROFILE_MISSING");
        require(installed, "search", profile.search().mode(), "VIRTUAL_SEARCH_PROXY_REQUIRED");
        require(installed, "storageStats", profile.storageStats().mode(),
                "VIRTUAL_STORAGE_STATS_PROXY_REQUIRED");
        require(installed, "graphicsStats", profile.graphicsStats().mode(),
                "VIRTUAL_GRAPHICS_STATS_PROXY_REQUIRED");
        require(installed, "contextHub", profile.contextHub().mode(),
                "VIRTUAL_CONTEXT_HUB_PROXY_REQUIRED");
        require(installed, "persistentDataBlock", profile.persistentDataBlock().mode(),
                "VIRTUAL_PERSISTENT_DATA_BLOCK_PROXY_REQUIRED");
        require(installed, "systemUpdate", profile.systemUpdate().mode(),
                "VIRTUAL_SYSTEM_UPDATE_PROXY_REQUIRED");
    }

    private static void require(
            Map<String, Boolean> installed, String key, String mode, String error) {
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(mode)
                && !Boolean.TRUE.equals(installed.get(key))) {
            throw new IllegalStateException(error);
        }
    }
}
