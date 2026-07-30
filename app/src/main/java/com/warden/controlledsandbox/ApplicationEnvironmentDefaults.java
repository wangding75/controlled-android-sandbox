package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualLauncherProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageStatsPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualUserProfileSnapshot;
import java.util.List;

/** Fail-closed defaults for app-environment services. */
final class ApplicationEnvironmentDefaults {
    private ApplicationEnvironmentDefaults() { }
    static ApplicationEnvironmentProfileSnapshot create(String packageName, int virtualUserId,
            long policyVersion, long updatedAtMs) {
        return new ApplicationEnvironmentProfileSnapshot(policyVersion, updatedAtMs,
                new VirtualUserProfileSnapshot(VirtualLocationProfileSnapshot.MODE_STATIC,
                        virtualUserId, 100_000L + virtualUserId, "Sandbox user " + virtualUserId,
                        0, true, true, false, List.of(), List.of(), List.of()),
                new VirtualLauncherProfileSnapshot(VirtualLocationProfileSnapshot.MODE_STATIC,
                        true, true, true, 16, List.of(packageName), List.of()),
                new VirtualShortcutPolicySnapshot(VirtualLocationProfileSnapshot.MODE_STATIC,
                        true, 15, 64, 100, 0L, false, true),
                new VirtualWidgetPolicySnapshot(VirtualLocationProfileSnapshot.MODE_STATIC,
                        true, false, false, 32, 8),
                new VirtualUsageStatsPolicySnapshot(VirtualLocationProfileSnapshot.MODE_STATIC,
                        true, 7L * 24L * 60L * 60L * 1000L, 4096, true, false),
                new VirtualSettingsProfileSnapshot(VirtualLocationProfileSnapshot.MODE_STATIC,
                        true, true, false, 512,
                        List.of(VirtualSettingsProfileSnapshot.NAMESPACE_SECURE,
                                VirtualSettingsProfileSnapshot.NAMESPACE_SYSTEM,
                                VirtualSettingsProfileSnapshot.NAMESPACE_GLOBAL),
                        List.of("adb_enabled", "development_settings_enabled", "device_provisioned",
                                "enabled_accessibility_services", "default_input_method", "location_mode")));
    }
}
