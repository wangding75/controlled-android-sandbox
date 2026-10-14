package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/**
 * Installs a safe-return proxy for Xiaomi/HyperOS miui.security.SecurityManager.
 *
 * <p>MIUI's SecurityManager exposes gate methods such as isAllowStartActivity and
 * checkAccessControl that return false for guest UIDs that are not in its allowlist.
 * Without a proxy, Quark and other MIUI-aware apps fail to start Activities and their
 * background management is disabled by getAppRunningControlEnabled returning true.</p>
 *
 * <p>On non-MIUI devices the service is absent; install() swallows the lookup failure and
 * returns a no-op hook so FrameworkHooks can call it unconditionally.</p>
 */
public final class MiuiSecurityManagerServiceHook {
    static final String ANDROID_SERVICE_NAME = "miui.security.SecurityManager";
    static final String LOGICAL_SERVICE_NAME = "miuisecurity";

    private MiuiSecurityManagerServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) {
        try {
            return ServiceManagerBinderHook.installDiscovered(
                    ANDROID_SERVICE_NAME, identity, LOGICAL_SERVICE_NAME);
        } catch (Exception absent) {
            // Service is not present on non-MIUI devices; return no-op.
            android.util.Log.i("CS_FRAMEWORK",
                    "MIUI SecurityManager unavailable (non-MIUI device): "
                            + absent.getMessage());
            return () -> { };
        }
    }
}
