package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Materializes a Guest-scoped TelecomManager. Radio-less or sealed Guest contexts otherwise
 * return null from {@code getSystemService("telecom")}, which production apps treat as fatal.
 */
public final class TelecomServiceHook {
    private static final String SERVICE = "telecom";
    private static final String MANAGER = "android.telecom.TelecomManager";

    private TelecomServiceHook() { }

    public static AutoCloseable install(Context guestContext, Context hostContext) throws Exception {
        if (guestContext == null) throw new IllegalArgumentException("guestContext is required");
        if (hostContext == null) throw new IllegalArgumentException("hostContext is required");
        Object manager = hostContext.getSystemService(SERVICE);
        if (manager == null) manager = createGuestManager(guestContext);
        if (manager == null) throw new IllegalStateException("TELECOM_MANAGER_UNAVAILABLE");
        return GuestSystemServiceOverrideRegistry.install(guestContext, SERVICE, manager);
    }

    private static Object createGuestManager(Context guestContext) {
        try {
            Class<?> managerType = Class.forName(MANAGER);
            Constructor<?> constructor = managerType.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            return constructor.newInstance(guestContext);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(cause);
            android.util.Log.w("CS_TELECOM", "context constructor failed", error);
            return null;
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_TELECOM", "context constructor unavailable", error);
            return null;
        }
    }
}
