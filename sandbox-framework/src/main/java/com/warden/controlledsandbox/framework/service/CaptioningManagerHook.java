package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import android.content.Context;

/**
 * Installs the Guest-scoped CaptioningManager used by framework clients such as WebView.
 *
 * <p>The platform manager reads caption settings through the supplied Context.  Constructing it
 * with the Guest Context therefore keeps those reads behind the existing virtual Settings
 * provider boundary.  Returning the Host Context's cached manager would expose Host settings and
 * would also make WebView's framework bridge depend on Host identity.</p>
 */
public final class CaptioningManagerHook {
    private static final String SERVICE = "captioning";
    private static final String MANAGER = "android.view.accessibility.CaptioningManager";

    private CaptioningManagerHook() { }

    public static AutoCloseable install(Context guestContext) throws Exception {
        if (guestContext == null) throw new IllegalArgumentException("guestContext is required");
        Object manager = createGuestManager(guestContext);
        return GuestSystemServiceOverrideRegistry.install(guestContext, SERVICE, manager);
    }

    private static Object createGuestManager(Context guestContext) throws Exception {
        Class<?> managerType = Class.forName(MANAGER);
        Constructor<?> constructor;
        try {
            constructor = managerType.getDeclaredConstructor(Context.class);
        } catch (NoSuchMethodException missingContextConstructor) {
            throw new IllegalStateException("CAPTIONING_MANAGER_CONTEXT_CONSTRUCTOR_UNAVAILABLE",
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
