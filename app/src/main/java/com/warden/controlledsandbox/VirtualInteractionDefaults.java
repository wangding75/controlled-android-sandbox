package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualDisplayProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplaySnapshot;
import com.warden.controlledsandbox.contract.VirtualInputMethodProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import java.util.List;

/** Deterministic host-independent defaults for Guest window/input/display behavior. */
final class VirtualInteractionDefaults {
    private VirtualInteractionDefaults() { }

    static VirtualInteractionProfileSnapshot create(String packageName, int virtualUserId,
            long version, long updatedAtMs) {
        int width = virtualUserId % 2 == 0 ? 1080 : 1200;
        int height = virtualUserId % 2 == 0 ? 1920 : 2000;
        VirtualWindowPolicySnapshot window = new VirtualWindowPolicySnapshot(
                VirtualWindowPolicySnapshot.MODE_STATIC, 32, true, true, false, false);
        VirtualInputMethodProfileSnapshot input = new VirtualInputMethodProfileSnapshot(
                VirtualWindowPolicySnapshot.MODE_STATIC, "", List.of(), false,
                false, false, true, 8);
        VirtualDisplaySnapshot primary = new VirtualDisplaySnapshot(
                0, "ControlledSandbox-u" + virtualUserId, width, height, 420,
                420f, 420f, 60f, 0, 2, 0, false);
        VirtualDisplayProfileSnapshot display = new VirtualDisplayProfileSnapshot(
                VirtualWindowPolicySnapshot.MODE_STATIC, 0, false, 0, List.of(primary));
        return new VirtualInteractionProfileSnapshot(version, updatedAtMs, window, input, display);
    }
}
