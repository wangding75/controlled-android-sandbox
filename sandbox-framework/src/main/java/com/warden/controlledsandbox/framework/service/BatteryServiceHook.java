package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Projects BatteryManager into the Guest Context and fences its two Binder dependencies.
 *
 * <p>BatteryManager is a framework facade, not a Context-owned manager in the sense of the
 * legacy service hooks.  Its no-argument constructor resolves {@code batterystats} and
 * {@code batteryproperties} directly from ServiceManager.  Returning the Host's manager here
 * would therefore leak a Host Context projection, while merely allow-listing the service would
 * still leave the two Binder calls outside the Guest identity boundary.</p>
 */
public final class BatteryServiceHook {
    private static final String BATTERY_SERVICE = "batterymanager";
    private static final String BATTERY_PROPERTIES = "batteryproperties";
    private static final String BATTERY_STATS = "batterystats";

    private BatteryServiceHook() { }

    public static AutoCloseable install(Context guestContext, GuestIdentity identity)
            throws Exception {
        List<AutoCloseable> installed = new ArrayList<>();
        try {
            // Install the Binder projections before constructing BatteryManager.  The platform
            // constructor resolves these services immediately on API32 and on current releases.
            installed.add(ServiceManagerBinderHook.installDiscovered(
                    BATTERY_PROPERTIES, identity, "batteryProperties"));
            installed.add(ServiceManagerBinderHook.installDiscovered(
                    BATTERY_STATS, identity, "batteryStats"));

            Object batteryManager = newBatteryManager();
            installed.add(GuestSystemServiceOverrideRegistry.install(
                    guestContext, BATTERY_SERVICE, batteryManager));
            return ReflectiveServiceHook.compose(installed.toArray(new AutoCloseable[0]));
        } catch (Throwable error) {
            for (int index = installed.size() - 1; index >= 0; index--) {
                try { installed.get(index).close(); } catch (Throwable rollback) {
                    error.addSuppressed(rollback);
                }
            }
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("VIRTUAL_BATTERY_INSTALL_FAILED", error);
        }
    }

    private static Object newBatteryManager() throws Exception {
        Class<?> type = Class.forName("android.os.BatteryManager");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object manager = constructor.newInstance();
        if (manager == null) throw new IllegalStateException("BatteryManager constructor returned null");
        return manager;
    }
}
