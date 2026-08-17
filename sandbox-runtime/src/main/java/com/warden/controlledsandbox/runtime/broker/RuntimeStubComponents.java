package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity0;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity1;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity2;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity3;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity4;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity5;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity6;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity7;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService0;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService1;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService2;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService3;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService4;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService5;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService6;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService7;
import com.warden.controlledsandbox.runtime.component.service.StubService0;
import com.warden.controlledsandbox.runtime.component.service.StubService1;
import com.warden.controlledsandbox.runtime.component.service.StubService2;
import com.warden.controlledsandbox.runtime.component.service.StubService3;
import com.warden.controlledsandbox.runtime.component.service.StubService4;
import com.warden.controlledsandbox.runtime.component.service.StubService5;
import com.warden.controlledsandbox.runtime.component.service.StubService6;
import com.warden.controlledsandbox.runtime.component.service.StubService7;

/** Immutable mapping from logical ordinary Guest slots to predeclared Android components. */
final class RuntimeStubComponents {
    private RuntimeStubComponents() { }

    static Class<?> serviceClassFor(int slot) {
        switch (slot) {
            case 0: return GuestProcessService0.class;
            case 1: return GuestProcessService1.class;
            case 2: return GuestProcessService2.class;
            case 3: return GuestProcessService3.class;
            case 4: return GuestProcessService4.class;
            case 5: return GuestProcessService5.class;
            case 6: return GuestProcessService6.class;
            case 7: return GuestProcessService7.class;
            default: return load("com.warden.controlledsandbox.runtime.guest.GuestProcessService" + slot);
        }
    }

    static Class<?> activityClassFor(int slot) {
        switch (slot) {
            case 0: return StubActivity0.class;
            case 1: return StubActivity1.class;
            case 2: return StubActivity2.class;
            case 3: return StubActivity3.class;
            case 4: return StubActivity4.class;
            case 5: return StubActivity5.class;
            case 6: return StubActivity6.class;
            case 7: return StubActivity7.class;
            default: return load("com.warden.controlledsandbox.runtime.component.activity.StubActivity" + slot);
        }
    }

    /**
     * Android's task matcher keys on the declared host component before the client-side
     * ActivityInfo projection runs. Keep a stable physical component per Guest Activity class so
     * CLEAR_TOP/SINGLE_TOP cannot accidentally select another Guest Activity sharing the same
     * process slot. The virtual manifest order is the allocation authority: the first declared
     * Guest Activity uses the original component and subsequent declarations use the matching
     * predeclared alias. This avoids hash collisions and keeps the mapping stable across process
     * death, recovery and package upgrades.
     */
    static String activityComponentFor(int slot, String guestComponent,
                                       VirtualPackageStateSnapshot packageState) {
        String base = activityClassFor(slot).getName();
        int variant = activityVariant(guestComponent, packageState);
        if (variant == 0) return base;
        if (variant < 0 || variant > 63) {
            throw new IllegalArgumentException("Guest Activity alias pool exhausted: "
                    + guestComponent + " index=" + variant);
        }
        // An alias still resolves to the target Activity's realActivity identity. That
        // identity is what ActivityTaskManager uses while applying CLEAR_TOP/SINGLE_TOP,
        // so aliases that share a target are not sufficient to isolate two Guest
        // Activities in the same process slot. Use a separately declared physical
        // Activity class for every slot/variant instead.
        int group = slot / 8;
        return "com.warden.controlledsandbox.runtime.component.activity.StubActivitySlotVariants"
                + group + "$S" + slot + "V" + variant;
    }

    static String activityComponentFor(int slot, String guestComponent) {
        String base = activityClassFor(slot).getName();
        int variant = guestComponent == null ? 0 : Math.floorMod(guestComponent.hashCode(), 4);
        if (variant == 0) return base;
        return "com.warden.controlledsandbox.runtime.component.activity.StubActivityAlias"
                + slot + "V" + variant;
    }

    private static int activityVariant(String guestComponent,
                                       VirtualPackageStateSnapshot packageState) {
        if (guestComponent == null || guestComponent.trim().isEmpty()) return -1;
        if (packageState != null) {
            int index = 0;
            for (VirtualComponentSnapshot component : packageState.components()) {
                if (!"ACTIVITY".equalsIgnoreCase(component.type())) continue;
                if (guestComponent.equals(component.className())) return index;
                index++;
            }
            return -1;
        }
        return Math.floorMod(guestComponent.hashCode(), 64);
    }

    static Class<?> componentServiceClassFor(int slot) {
        switch (slot) {
            case 0: return StubService0.class;
            case 1: return StubService1.class;
            case 2: return StubService2.class;
            case 3: return StubService3.class;
            case 4: return StubService4.class;
            case 5: return StubService5.class;
            case 6: return StubService6.class;
            case 7: return StubService7.class;
            default: return load("com.warden.controlledsandbox.runtime.component.service.StubService" + slot);
        }
    }

    private static Class<?> load(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException error) { throw new IllegalArgumentException("Missing process slot stub: " + name, error); }
    }
}
