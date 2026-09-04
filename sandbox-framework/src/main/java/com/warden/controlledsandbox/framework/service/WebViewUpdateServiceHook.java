package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import android.os.IBinder;
import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;
import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Reversible IWebViewUpdateService projection. */
public final class WebViewUpdateServiceHook {
    private WebViewUpdateServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return install(null, identity);
    }

    /**
     * Installs both sides of the API 37 WebView boundary. Android 17's WebViewFactory no longer
     * necessarily consumes the ServiceManager Binder directly: when the update-service IPC
     * wrapper flag is enabled it asks the initial Application for a WebViewUpdateManager. The
     * Binder projection alone therefore leaves the manager lookup null and fails before the
     * projected provider response can be observed.
     */
    public static AutoCloseable install(Context guestContext, GuestIdentity identity)
            throws Exception {
        if (identity == null) throw new IllegalArgumentException("identity is required");
        android.util.Log.i("CS_WEBVIEW_PROXY", "INSTALL_BEGIN api="
                + android.os.Build.VERSION.SDK_INT + " guestContext="
                + (guestContext == null ? "null" : guestContext.getClass().getName()));
        AutoCloseable binder = ServiceManagerBinderHook.install(
                "webviewupdate",
                "android.webkit.IWebViewUpdateService$Stub",
                identity,
                "webviewupdate");
        if (android.os.Build.VERSION.SDK_INT < 37) return binder;
        if (guestContext == null) {
            try { binder.close(); } catch (Exception ignored) { }
            throw new IllegalArgumentException("guestContext is required on API37+");
        }
        try {
            AutoCloseable manager = installGuestManager(guestContext);
            return () -> {
                try {
                    manager.close();
                } finally {
                    binder.close();
                }
            };
        } catch (Throwable error) {
            try { binder.close(); } catch (Exception rollback) { error.addSuppressed(rollback); }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("VIRTUAL_WEBVIEW_UPDATE_MANAGER_INSTALL_FAILED", error);
        }
    }

    private static AutoCloseable installGuestManager(Context guestContext) throws Exception {
        Class<?> managerType = Class.forName("android.webkit.WebViewUpdateManager");
        IBinder binder = serviceBinder("webviewupdate");
        Class<?> stubType = Class.forName("android.webkit.IWebViewUpdateService$Stub");
        Method asInterface = findAsInterface(stubType);
        Object service = asInterface.invoke(null, binder);
        if (service == null) {
            throw new IllegalStateException("WEBVIEW_UPDATE_SERVICE_INTERFACE_UNAVAILABLE");
        }
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : managerType.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(service)) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) {
            throw new NoSuchMethodException("WebViewUpdateManager(IWebViewUpdateService)");
        }
        constructor.setAccessible(true);
        Object manager = constructor.newInstance(service);
        AutoCloseable override = GuestSystemServiceOverrideRegistry.install(
                guestContext, "webviewupdate", manager);
        try {
            Object observed = guestContext.getSystemService(managerType);
            if (observed != manager) {
                override.close();
                throw new IllegalStateException("WEBVIEW_UPDATE_MANAGER_GUEST_LOOKUP_MISMATCH");
            }
            android.util.Log.i("CS_WEBVIEW_PROXY", "API37 manager=GUEST_CONTEXT_OVERRIDE"
                    + " binder=PROJECTED_SERVICE_INTERFACE");
            return override;
        } catch (Throwable error) {
            try { override.close(); } catch (Exception rollback) { error.addSuppressed(rollback); }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("WEBVIEW_UPDATE_MANAGER_GUEST_LOOKUP_FAILED", error);
        }
    }

    private static IBinder serviceBinder(String name) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object value = getService.invoke(null, name);
        if (!(value instanceof IBinder binder)) {
            throw new IllegalStateException("WEBVIEW_UPDATE_SERVICE_BINDER_UNAVAILABLE");
        }
        return binder;
    }

    private static Method findAsInterface(Class<?> stub) throws NoSuchMethodException {
        for (Method method : stub.getDeclaredMethods()) {
            if ("asInterface".equals(method.getName()) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(stub.getName() + ".asInterface");
    }
}
