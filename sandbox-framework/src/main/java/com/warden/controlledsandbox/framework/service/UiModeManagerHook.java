package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Installs the Guest-scoped UiModeManager used by API37 WebView initialization.
 *
 * <p>Android 17's WebView reads contrast through {@code UiModeManager}. The Host Context's
 * service cache is not a valid Guest Context projection, so materialize the framework manager
 * with the Guest Context and expose it only through the Guest override registry.</p>
 */
public final class UiModeManagerHook {
    private static final String SERVICE = "uimode";
    private static final String MANAGER = "android.app.UiModeManager";

    private UiModeManagerHook() { }

    public static AutoCloseable install(Context guestContext) throws Exception {
        if (guestContext == null) throw new IllegalArgumentException("guestContext is required");
        if (android.os.Build.VERSION.SDK_INT < 37) return () -> { };
        Object manager = createGuestManager(guestContext);
        AutoCloseable override = GuestSystemServiceOverrideRegistry.install(
                guestContext, SERVICE, manager);
        android.util.Log.i("CS_UIMODE_PROXY", "API37 manager=GUEST_CONTEXT_OVERRIDE");
        return override;
    }

    private static Object createGuestManager(Context guestContext) throws Exception {
        Class<?> managerType = Class.forName(MANAGER);
        Constructor<?> constructor;
        try {
            constructor = managerType.getDeclaredConstructor(Context.class);
        } catch (NoSuchMethodException missingContextConstructor) {
            throw new IllegalStateException("UIMODE_MANAGER_CONTEXT_CONSTRUCTOR_UNAVAILABLE",
                    missingContextConstructor);
        }
        constructor.setAccessible(true);
        try {
            return constructor.newInstance(guestContext);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }
}
