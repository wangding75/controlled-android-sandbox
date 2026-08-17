package com.warden.controlledsandbox.runtime.broker;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Host-owned Binder transport for Android isolated UIDs.
 *
 * <p>An isolated UID can receive a Binder handle but cannot discover most framework services
 * through its own ServiceManager policy. A relay keeps the interface transaction unchanged and
 * performs the downstream system-service transaction in the host Broker process, so the kernel
 * sees the Broker identity at the service boundary. Only the explicit allow-list is exported and
 * each relay is session-scoped by the Bundle that carries it.</p>
 */
public final class FrameworkServiceRelay extends Binder {
    private static final String[] SERVICE_NAMES = {
            "activity", "activity_task", "window", "input", "input_method", "display",
            "package", "appops", "permission", "notification", "jobscheduler", "alarm",
            "clipboard", "account", "storage", "mount", "user", "connectivity", "netd",
            "dnsresolver", "vpnmanagement", "vpn_management", "location", "phone",
            "iphonesubinfo", "isub",
            "telephony.registry", "wifi", "wifiscanner", "bluetooth_manager", "sensorservice",
            "media.camera",
            "audio", "media_session", "media_router", "nfc", "persistent_data_block",
            "system_update", "webviewupdate", "content", "search", "device_policy",
            "accessibility", "autofill", "biometric", "sensor_privacy", "power", "vibrator",
            "companiondevice", "shortcut", "appwidget", "launcherapps", "usagestats", "media_projection",
            "usb", "print", "country_detection", "network_management", "network_score",
            "stats", "graphicsstats", "device_identifiers", "restrictions", "backup", "dropbox",
            "storagestats", "thermalservice"
    };

    private final IBinder target;

    private FrameworkServiceRelay(IBinder target) {
        if (target == null) throw new IllegalArgumentException("target is required");
        this.target = target;
    }

    /** Captures only the allow-listed framework service handles in the host process. */
    public static Bundle capture() {
        Bundle result = new Bundle();
        int captured = 0;
        for (String name : SERVICE_NAMES) {
            try {
                IBinder service = getService(name);
                if (service != null) {
                    result.putBinder(name, new FrameworkServiceRelay(service));
                    captured++;
                }
            } catch (Throwable error) {
                android.util.Log.w("CS_FRAMEWORK_RELAY", "capture failed service=" + name, error);
            }
        }
        result.putInt("capturedCount", captured);
        android.util.Log.i("CS_FRAMEWORK_RELAY", "captured=" + captured
                + " allowList=" + SERVICE_NAMES.length);
        return result;
    }

    /** Installs captured relays into the isolated process' ServiceManager cache before hooks. */
    public static int install(Bundle relays) {
        if (relays == null || relays.isEmpty()) return 0;
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Field cacheField = findField(serviceManager, "sCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (!(cache instanceof Map)) {
                throw new IllegalStateException("ServiceManager cache is not a Map");
            }
            @SuppressWarnings("unchecked") Map<String, IBinder> map = (Map<String, IBinder>) cache;
            int installed = 0;
            for (String name : relays.keySet()) {
                IBinder binder = relays.getBinder(name);
                if (binder != null && !"capturedCount".equals(name)) {
                    map.put(name, binder);
                    installed++;
                }
            }
            android.util.Log.i("CS_FRAMEWORK_RELAY", "installed=" + installed);
            return installed;
        } catch (Throwable error) {
            android.util.Log.e("CS_FRAMEWORK_RELAY", "install failed", error);
            throw new IllegalStateException("ISOLATED_FRAMEWORK_SERVICE_RELAY_INSTALL_FAILED", error);
        }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        return target.transact(code, data, reply, flags);
    }

    private static IBinder getService(String name) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method method = serviceManager.getDeclaredMethod("getService", String.class);
        method.setAccessible(true);
        return (IBinder) method.invoke(null, name);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
