package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fail-closed launch gate for configured Window/Input/Display virtualization. */
final class InteractionProxyReadiness {
    private InteractionProxyReadiness() { }

    static void require(Map<String, Boolean> installed, VirtualInteractionProfileSnapshot profile) {
        if (installed == null || profile == null) {
            throw new IllegalStateException("VIRTUAL_INTERACTION_READINESS_INPUT_REQUIRED");
        }
        List<String> missing = new ArrayList<>();
        requireDomain(profile.window().mode(), installed, missing, "window", "activityClient");
        requireDomain(profile.inputMethod().mode(), installed, missing, "inputMethod");
        requireDomain(profile.display().mode(), installed, missing, "display");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("VIRTUAL_INTERACTION_PROXY_REQUIRED:"
                    + String.join(",", missing));
        }
    }

    private static void requireDomain(String mode, Map<String, Boolean> installed,
            List<String> missing, String... hooks) {
        if (VirtualWindowPolicySnapshot.MODE_HOST.equals(mode)) return;
        for (String hook : hooks) if (!Boolean.TRUE.equals(installed.get(hook))) missing.add(hook);
    }
}
