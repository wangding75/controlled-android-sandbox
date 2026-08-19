package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import java.util.Set;

/**
 * Physical Host Activity identity is a window-compatibility dimension, not a Guest
 * declaration index. Android must distinguish opaque vs translucent windows before
 * {@code Activity.onCreate}; launchMode and task affinity stay virtual.
 */
public enum PhysicalActivityWindowFamily {
    OPAQUE,
    TRANSLUCENT;

    static final int ORDINARY_SLOT_COUNT = 64;
    /**
     * Bounded activity-window multiplexing: within one process slot the physical ComponentName is
     * no longer a single shared Stub class.  A live virtual Activity owns one window index from a
     * fixed pool so Android's ActivityStarter can reorder / clear-top / single-top match exactly
     * that ActivityRecord instead of the topmost sibling that shares the old single stub class.
     * The manifest stays a constant (slot x window x family); it never grows with Guest declarations.
     */
    public static final int WINDOW_SLOT_COUNT = 16;
    private static final String PACKAGE = "com.warden.controlledsandbox.runtime.component.activity.";

    /**
     * Framework theme resource IDs that create a translucent or dialog window before the
     * Guest theme can be re-applied. Custom Guest themes default to {@link #OPAQUE}; the
     * Guest theme is still projected after attach.
     */
    private static final Set<Integer> TRANSLUCENT_OR_DIALOG_THEMES = Set.of(
            16973830, 16973831, 16973832, 16973835,
            16973935, 16973936, 16973937, 16973940, 16973941,
            16974126, 16974128, 16974130, 16974132,
            16974146, 16974147, 16974148,
            16974372, 16974373, 16974374, 16974375, 16974376, 16974377,
            16974378, 16974379, 16974380, 16974381, 16974382, 16974383,
            16974545, 16974546, 16974547, 16974548, 16974549, 16974550);

    public String componentName(int slot) {
        return componentName(slot, 0);
    }

    public String componentName(int slot, int window) {
        requireSlot(slot);
        requireWindow(window);
        if (this == TRANSLUCENT) {
            return window == 0
                    ? PACKAGE + "StubActivityTranslucent" + slot
                    : PACKAGE + "StubActivityTranslucent" + slot + "W" + window;
        }
        return window == 0
                ? PACKAGE + "StubActivity" + slot
                : PACKAGE + "StubActivity" + slot + "W" + window;
    }

    public static PhysicalActivityWindowFamily of(String guestComponent,
            VirtualPackageStateSnapshot packageState) {
        VirtualComponentSnapshot component = requireActivity(guestComponent, packageState);
        return ofTheme(component.themeResId());
    }

    static PhysicalActivityWindowFamily ofTheme(int themeResId) {
        return TRANSLUCENT_OR_DIALOG_THEMES.contains(themeResId) ? TRANSLUCENT : OPAQUE;
    }

    static VirtualComponentSnapshot requireActivity(
            String guestComponent, VirtualPackageStateSnapshot packageState) {
        if (guestComponent == null || guestComponent.trim().isEmpty()) {
            throw new IllegalArgumentException("GUEST_ACTIVITY_NOT_IN_PACKAGE_STATE:");
        }
        if (packageState == null) {
            throw new IllegalArgumentException("GUEST_ACTIVITY_NOT_IN_PACKAGE_STATE:"
                    + guestComponent);
        }
        for (VirtualComponentSnapshot component : packageState.components()) {
            if ("ACTIVITY".equalsIgnoreCase(component.type())
                    && guestComponent.equals(component.className())) {
                return component;
            }
        }
        throw new IllegalArgumentException("GUEST_ACTIVITY_NOT_IN_PACKAGE_STATE:"
                + guestComponent);
    }

    public static void requireSlot(int slot) {
        if (slot < 0 || slot >= ORDINARY_SLOT_COUNT) {
            throw new IllegalArgumentException("ordinary process slot out of range: " + slot);
        }
    }

    public static void requireWindow(int window) {
        if (window < 0 || window >= WINDOW_SLOT_COUNT) {
            throw new IllegalArgumentException("activity window slot out of range: " + window);
        }
    }
}
