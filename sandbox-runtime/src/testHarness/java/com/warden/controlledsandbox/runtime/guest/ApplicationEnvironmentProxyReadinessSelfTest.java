package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualLauncherProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageStatsPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualUserProfileSnapshot;
import java.util.List;
import java.util.Map;

public final class ApplicationEnvironmentProxyReadinessSelfTest {
    public static void main(String[] args) {
        Map<String, Boolean> installed = Map.of("userManager", true, "launcherApps", true,
                "shortcut", true, "appWidget", true, "usageStats", true,
                "content", true, "settingsIdentity", true);
        ApplicationEnvironmentProxyReadiness.require(installed, profile(VirtualLocationProfileSnapshot.MODE_STATIC));
        boolean blocked = false;
        try { ApplicationEnvironmentProxyReadiness.require(Map.of("userManager", true),
                profile(VirtualLocationProfileSnapshot.MODE_STATIC)); }
        catch (IllegalStateException expected) { blocked = expected.getMessage().contains("launcherApps"); }
        require(blocked, "missing application-environment hooks must block launch");
        ApplicationEnvironmentProxyReadiness.require(Map.of(), profile(VirtualLocationProfileSnapshot.MODE_HOST));
        System.out.println("PASS M5-T11 application-environment proxy readiness self-test");
    }

    private static ApplicationEnvironmentProfileSnapshot profile(String mode) {
        return new ApplicationEnvironmentProfileSnapshot(1L, 0L,
                new VirtualUserProfileSnapshot(mode, 0, 100000L, "User", 0, true, true, false,
                        List.of(), List.of(), List.of()),
                new VirtualLauncherProfileSnapshot(mode, true, true, true, 8, List.of(), List.of()),
                new VirtualShortcutPolicySnapshot(mode, true, 15, 64, 100, 0L, false, true),
                new VirtualWidgetPolicySnapshot(mode, true, false, false, 32, 8),
                new VirtualUsageStatsPolicySnapshot(mode, true, 60000L, 100, true, false),
                new VirtualSettingsProfileSnapshot(mode, true, true, false, 128,
                        List.of("secure", "system", "global"), List.of()));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
