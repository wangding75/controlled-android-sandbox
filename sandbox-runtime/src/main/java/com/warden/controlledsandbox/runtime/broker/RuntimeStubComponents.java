package com.warden.controlledsandbox.runtime.broker;

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
            default: throw new IllegalArgumentException("Invalid process slot: " + slot);
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
            default: throw new IllegalArgumentException("Invalid process slot: " + slot);
        }
    }
}
