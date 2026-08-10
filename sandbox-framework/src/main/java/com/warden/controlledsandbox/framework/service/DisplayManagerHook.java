package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/**
 * DisplayManagerGlobal virtualization with the ServiceManager boundary and manager caches kept
 * in sync. API32 and API35 both use the same IDisplayManager Binder contract even though cache
 * fields differ by release.
 */
public final class DisplayManagerHook {
    static final String DISPLAY_DESCRIPTOR = "android.hardware.display.IDisplayManager";

    private DisplayManagerHook() { }

    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        AutoCloseable serviceManager = null;
        AutoCloseable manager = null;
        AutoCloseable caches = null;
        try {
            serviceManager = ReflectiveServiceHook.serviceManagerBinding(
                    "display", "display", DISPLAY_DESCRIPTOR, identity);
            manager = ReflectiveServiceHook.managerFieldCandidatesWithDescriptor(
                    context, "display", "display", DISPLAY_DESCRIPTOR, identity,
                    "mGlobal.mDm", "mDm", "mGlobal.mDisplayManager");
            caches = ReflectiveServiceHook.clearManagerCaches(context, "display",
                    "mGlobal.mDisplayInfoCache", "mGlobal.mDisplayIdCache",
                    "mDisplays", "mTempDisplays");
            android.util.Log.i("CS_INTERACTION_PROXY",
                    "DISPLAY_STRUCTURE_READY descriptor=" + DISPLAY_DESCRIPTOR
                            + " manager=bound cache=synchronized");
            return ReflectiveServiceHook.compose(caches, manager, serviceManager);
        } catch (Throwable error) {
            close(caches, error);
            close(manager, error);
            close(serviceManager, error);
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("DISPLAY_PROXY_INSTALL_FAILED", error);
        }
    }

    private static void close(AutoCloseable hook, Throwable error) {
        if (hook == null) return;
        try { hook.close(); } catch (Throwable rollback) { error.addSuppressed(rollback); }
    }
}
