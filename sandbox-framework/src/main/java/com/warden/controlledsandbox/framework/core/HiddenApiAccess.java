package com.warden.controlledsandbox.framework.core;

import java.lang.reflect.Method;

/** One platform-wide hidden-API access point for the audited framework compatibility layer. */
final class HiddenApiAccess {
    private static boolean attempted;

    private HiddenApiAccess() { }

    static void ensureExemptions() {
        synchronized (HiddenApiAccess.class) {
            if (attempted) {
                return;
            }
            attempted = true;
            try {
                Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
                Method getRuntime = runtime.getDeclaredMethod("getRuntime");
                Method setExemptions = runtime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
                getRuntime.setAccessible(true);
                setExemptions.setAccessible(true);
                Object instance = getRuntime.invoke(null);
                setExemptions.invoke(instance, (Object) new String[] {"L"});
            } catch (ClassNotFoundException hostJvm) {
                // Host/static compilation has no Dalvik runtime and must remain deterministic.
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
                // Android builds may deny the VMRuntime exemption itself while still allowing
                // individual @UnsupportedAppUsage members.  Keep the audited reflection path
                // alive and let the descriptor/field contract fail closed per service.
            }
        }
    }
}
