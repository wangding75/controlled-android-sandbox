package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fail-closed launch gate for configured application-environment virtualization. */
final class ApplicationEnvironmentProxyReadiness {
    private ApplicationEnvironmentProxyReadiness() { }

    static void require(Map<String, Boolean> installed,
            ApplicationEnvironmentProfileSnapshot profile) {
        if (installed == null || profile == null) {
            throw new IllegalStateException("VIRTUAL_APPLICATION_ENVIRONMENT_READINESS_INPUT_REQUIRED");
        }
        List<String> missing = new ArrayList<>();
        requireDomain(profile.user().mode(), installed, missing, "userManager");
        if (!profile.user().applicationRestrictionKeys().isEmpty()) {
            requireDomain(profile.user().mode(), installed, missing, "restrictions");
        }
        requireDomain(profile.launcher().mode(), installed, missing, "launcherApps");
        requireDomain(profile.shortcut().mode(), installed, missing, "shortcut");
        requireDomain(profile.appWidget().mode(), installed, missing, "appWidget");
        requireDomain(profile.usageStats().mode(), installed, missing, "usageStats");
        requireDomain(profile.settings().mode(), installed, missing, "content", "settingsIdentity");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("VIRTUAL_APPLICATION_ENVIRONMENT_PROXY_REQUIRED:"
                    + String.join(",", missing));
        }
    }

    private static void requireDomain(String mode, Map<String, Boolean> installed,
            List<String> missing, String... hooks) {
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(mode)) return;
        for (String hook : hooks) {
            if (!Boolean.TRUE.equals(installed.get(hook)) && !missing.contains(hook)) missing.add(hook);
        }
    }
}
