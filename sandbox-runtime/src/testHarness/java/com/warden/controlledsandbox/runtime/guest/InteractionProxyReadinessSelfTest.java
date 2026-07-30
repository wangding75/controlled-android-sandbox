package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualDisplayProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplaySnapshot;
import com.warden.controlledsandbox.contract.VirtualInputMethodProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fail-closed source tests for M5-T9 interaction proxy readiness. */
public final class InteractionProxyReadinessSelfTest {
    public static void main(String[] args) {
        Map<String, Boolean> installed = new LinkedHashMap<>();
        installed.put("window", true);
        installed.put("activityClient", true);
        installed.put("inputMethod", true);
        installed.put("display", true);
        InteractionProxyReadiness.require(installed, profile(VirtualWindowPolicySnapshot.MODE_STATIC));

        installed.put("activityClient", false);
        boolean blocked = false;
        try { InteractionProxyReadiness.require(installed,
                profile(VirtualWindowPolicySnapshot.MODE_STATIC)); }
        catch (IllegalStateException expected) {
            blocked = expected.getMessage().contains("activityClient");
        }
        require(blocked, "missing ActivityClient hook blocks launch");
        InteractionProxyReadiness.require(Map.of(), profile(VirtualWindowPolicySnapshot.MODE_HOST));
        System.out.println("PASS M5-T9 interaction proxy readiness self-test");
    }

    private static VirtualInteractionProfileSnapshot profile(String mode) {
        VirtualWindowPolicySnapshot window = new VirtualWindowPolicySnapshot(mode, 4,
                true, false, false, false);
        VirtualInputMethodProfileSnapshot input = new VirtualInputMethodProfileSnapshot(mode,
                "", List.of(), false, false, false, true, 4);
        VirtualDisplaySnapshot display = new VirtualDisplaySnapshot(0, "Virtual display",
                1080, 1920, 420, 420f, 420f, 60f, 0, 2, 0, true);
        VirtualDisplayProfileSnapshot displays = new VirtualDisplayProfileSnapshot(mode, 0,
                false, 0, List.of(display));
        return new VirtualInteractionProfileSnapshot(1L, 1L, window, input, displays);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
